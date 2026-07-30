package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.MigrationGenerator
import org.jmixworkbench.model.AssociationCollectionType
import org.jmixworkbench.model.AssociationJoinColumn
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.CascadeType
import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.DbChange
import org.jmixworkbench.model.FetchType
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.InheritanceConfig
import org.jmixworkbench.model.InheritanceRole
import org.jmixworkbench.model.InheritanceStrategy
import org.jmixworkbench.model.JoinTableConfig
import org.jmixworkbench.model.MigrationModel
import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EmbeddedAssociationOverride
import org.jmixworkbench.model.EmbeddedAttributeOverride
import org.jmixworkbench.model.LifecycleCallback
import org.jmixworkbench.model.TraitType
import org.jmixworkbench.model.ValidationModel
import org.jmixworkbench.model.ValidationType
import java.time.LocalDate
import java.util.Locale

@Service(Service.Level.PROJECT)
class SchemaWorkspaceService(
    private val project: Project,
) {
    fun load(forceRefresh: Boolean = false): SchemaWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        val fileCache = linkedMapOf<String, String?>()
        fun content(path: String): String? = fileCache.getOrPut(path) { read(path) }

        val liquibaseFiles = graph.artifacts
            .filter { it.kind == ArtifactKind.LIQUIBASE_ROOT }
            .distinctBy { it.sourceLocator.relativePath }
            .sortedBy { it.sourceLocator.relativePath }
        val configuredStores = graph.artifacts
            .filter {
                it.kind == ArtifactKind.CONFIGURATION_PROPERTY &&
                    it.displayName.endsWith(".liquibase.change-log")
            }
            .map { property ->
                val storeName = property.displayName.removeSuffix(".liquibase.change-log")
                val configuredPath = property.summary.orEmpty()
                    .substringBefore('#')
                    .trim()
                    .removePrefix("classpath:")
                    .removePrefix("/")
                val root = resolveConfiguredRoot(configuredPath, liquibaseFiles)
                SchemaDataStoreSnapshot(
                    id = "${property.owner.moduleId}:$storeName",
                    name = storeName,
                    moduleId = property.owner.moduleId,
                    configuredPath = configuredPath,
                    configurationLocator = property.sourceLocator,
                    rootChangelogPath = root?.sourceLocator?.relativePath,
                    rootLocator = root?.sourceLocator,
                    includeMode = root?.let { includeMode(content(it.sourceLocator.relativePath).orEmpty()) }
                        ?: SchemaIncludeMode.MISSING,
                    includeTargets = root?.let { parseIncludes(content(it.sourceLocator.relativePath).orEmpty()) }
                        ?: emptyList(),
                    generatedDirectory = root?.let {
                        recommendedDirectory(it.sourceLocator.relativePath, content(it.sourceLocator.relativePath).orEmpty())
                    },
                )
            }
            .distinctBy(SchemaDataStoreSnapshot::id)
            .sortedWith(compareBy(SchemaDataStoreSnapshot::moduleId, SchemaDataStoreSnapshot::name))

        val inferredStores = if (configuredStores.isEmpty()) {
            liquibaseFiles.mapNotNull { artifact ->
                val source = content(artifact.sourceLocator.relativePath).orEmpty()
                if (!hasIncludes(source)) return@mapNotNull null
                SchemaDataStoreSnapshot(
                    id = "${artifact.owner.moduleId}:main",
                    name = "main",
                    moduleId = artifact.owner.moduleId,
                    configuredPath = classpathPath(artifact.sourceLocator.relativePath),
                    configurationLocator = null,
                    rootChangelogPath = artifact.sourceLocator.relativePath,
                    rootLocator = artifact.sourceLocator,
                    includeMode = includeMode(source),
                    includeTargets = parseIncludes(source),
                    generatedDirectory = recommendedDirectory(artifact.sourceLocator.relativePath, source),
                )
            }.distinctBy(SchemaDataStoreSnapshot::id)
        } else {
            emptyList()
        }
        val datasourceStores = graph.artifacts
            .filter {
                it.kind == ArtifactKind.CONFIGURATION_PROPERTY &&
                    it.displayName.endsWith(".datasource.url")
            }
            .map { property ->
                val storeName = property.displayName.removeSuffix(".datasource.url")
                SchemaDataStoreSnapshot(
                    id = "${property.owner.moduleId}:$storeName",
                    name = storeName,
                    moduleId = property.owner.moduleId,
                    configuredPath = "",
                    configurationLocator = property.sourceLocator,
                    rootChangelogPath = null,
                    rootLocator = null,
                    includeMode = SchemaIncludeMode.MISSING,
                    includeTargets = emptyList(),
                    generatedDirectory = null,
                )
            }
            .distinctBy(SchemaDataStoreSnapshot::id)
        val stores = (configuredStores + inferredStores + datasourceStores)
            .distinctBy(SchemaDataStoreSnapshot::id)
            .sortedWith(compareBy(SchemaDataStoreSnapshot::moduleId, SchemaDataStoreSnapshot::name))

        val changelogs = liquibaseFiles.map { artifact ->
            val source = content(artifact.sourceLocator.relativePath).orEmpty()
            SchemaChangelogSnapshot(
                artifactId = artifact.id,
                moduleId = artifact.owner.moduleId,
                relativePath = artifact.sourceLocator.relativePath,
                sourceLocator = artifact.sourceLocator,
                root = stores.any { it.rootChangelogPath == artifact.sourceLocator.relativePath },
                changeSetCount = CHANGESET_TAG.findAll(source).count(),
                includes = parseIncludes(source),
                tables = TABLE_REFERENCE.findAll(source)
                    .map { it.groupValues[1].uppercase(Locale.ROOT) }
                    .distinct()
                    .sorted()
                    .toList(),
                containsRawSql = RAW_SQL.containsMatchIn(source),
            )
        }
        val schemaTables = graph.artifacts
            .filter { it.kind == ArtifactKind.SCHEMA_OBJECT }
            .groupBy { it.displayName.uppercase(Locale.ROOT) }
        val attributesByEntity = entityAttributes(graph.artifacts, graph.relationships)
        val rawEntities = graph.artifacts
            .filter { it.kind == ArtifactKind.ENTITY }
            .map { entity ->
                val source = content(entity.sourceLocator.relativePath).orEmpty()
                val entityType = entityType(source)
                val tableName = if (entityType == EntityType.ENTITY) tableName(entity, source) else ""
                val storeName = STORE_ANNOTATION.find(source)?.groupValues?.get(1) ?: "main"
                val ddlMode = if (entityType == EntityType.ENTITY) {
                    ddlMode(source)
                } else {
                    SchemaDdlMode.DISABLED
                }
                val idMapping = idMapping(source)
                val hierarchy = entityHierarchy(source, entity.displayName)
                val attributes = attributesByEntity[entity.id].orEmpty().map { attribute ->
                    val name = attribute.displayName
                    val type = attribute.summary.orEmpty().substringBefore(" attribute of").trim()
                    val declaration = fieldDeclaration(source, name)
                    val metadata = attributeMetadata(source, name, declaration)
                    val embedded = isEmbedded(declaration)
                    val association = associationSnapshot(
                        source = source,
                        fieldName = name,
                        fallbackType = type,
                        ownerQualifiedName = entity.semanticKey,
                    )
                    SchemaEntityAttributeSnapshot(
                        artifactId = attribute.id,
                        name = name,
                        javaType = type,
                        columnName = association?.joinColumnName ?: columnName(source, name),
                        nullable = fieldNullable(
                            source,
                            association?.localIdAttributeName ?: name,
                        ),
                        unique = fieldUnique(
                            source,
                            association?.localIdAttributeName ?: name,
                        ),
                        length = fieldIntegerArgument(
                            source,
                            association?.localIdAttributeName ?: name,
                            "length",
                        ),
                        precision = fieldIntegerArgument(
                            source,
                            association?.localIdAttributeName ?: name,
                            "precision",
                        ),
                        scale = fieldIntegerArgument(
                            source,
                            association?.localIdAttributeName ?: name,
                            "scale",
                        ),
                        sqlType = metadata.sqlType,
                        persistent = entityType != EntityType.DTO &&
                            !TRANSIENT_ANNOTATION.containsMatchIn(declaration),
                        association = !embedded && (association != null || run {
                            val normalizedType = type.trim().removeSuffix("?").trim()
                            val simpleType = normalizedType.substringAfterLast('.').substringBefore('<')
                            normalizedType.contains('<') || (
                                simpleType.firstOrNull()?.isUpperCase() == true &&
                                    simpleType !in SCALAR_TYPES
                                )
                        }),
                        associationDetails = association,
                        embedded = embedded,
                        embeddedClass = if (embedded) {
                            resolveJavaType(source, entity.semanticKey, type)
                        } else {
                            null
                        },
                        embeddedAttributeOverrides = if (embedded) {
                            embeddedAttributeOverrides(declaration)
                        } else {
                            emptyList()
                        },
                        embeddedAssociationOverrides = if (embedded) {
                            embeddedAssociationOverrides(declaration)
                        } else {
                            emptyList()
                        },
                        moneyCandidate = MONEY_NAME.containsMatchIn(name),
                        comment = metadata.comment,
                        systemLevel = metadata.systemLevel,
                        lob = metadata.lob,
                        jmixProperty = metadata.jmixProperty,
                        dependsOnProperties = metadata.dependsOnProperties,
                        propertyDatatype = metadata.propertyDatatype,
                        validations = metadata.validations,
                        readOnly = metadata.readOnly,
                        unmanagedAnnotations = metadata.unmanagedAnnotations,
                    )
                }
                val migratedBy = schemaTables[tableName.uppercase(Locale.ROOT)].orEmpty()
                    .map(ArtifactSnapshot::id)
                SchemaEntitySnapshot(
                    artifactId = entity.id,
                    moduleId = entity.owner.moduleId,
                    className = entity.displayName,
                    qualifiedName = entity.semanticKey,
                    entityType = entityType,
                    entityName = ENTITY_ANNOTATION.find(source)?.groupValues?.get(1)
                        ?.takeIf(String::isNotBlank)
                        ?: entity.displayName,
                    tableName = tableName,
                    tableSchema = TABLE_SCHEMA_ANNOTATION.find(source)
                        ?.groupValues?.get(1)?.takeIf(String::isNotBlank),
                    tableCatalog = TABLE_CATALOG_ANNOTATION.find(source)
                        ?.groupValues?.get(1)?.takeIf(String::isNotBlank),
                    storeName = storeName,
                    idType = idMapping.first,
                    idColumnName = idMapping.second,
                    databaseView = DB_VIEW.containsMatchIn(source),
                    ddlMode = ddlMode,
                    protectedUnmappedColumns = ddlUnmappedColumns(source),
                    sourceLocator = entity.sourceLocator,
                    attributes = attributes,
                    migrationCoverage = when {
                        entityType != EntityType.ENTITY -> SchemaMigrationCoverage.DISABLED
                        ddlMode == SchemaDdlMode.DISABLED -> SchemaMigrationCoverage.DISABLED
                        migratedBy.isNotEmpty() -> SchemaMigrationCoverage.COVERED
                        else -> SchemaMigrationCoverage.MISSING
                    },
                    migrationArtifactIds = migratedBy,
                    traits = entityTraits(source, idMapping.first),
                    extendsClass = hierarchy.extendsClass,
                    implementsInterfaces = hierarchy.interfaces,
                    lifecycleCallbacks = lifecycleCallbacks(source),
                    entityListeners = entityListeners(source),
                    inheritance = inheritanceSnapshot(source),
                )
            }
            .sortedWith(compareBy(SchemaEntitySnapshot::moduleId, SchemaEntitySnapshot::qualifiedName))
        val entitiesByType = rawEntities.associateBy(SchemaEntitySnapshot::qualifiedName)
        val entitiesBySimpleName = rawEntities.groupBy(SchemaEntitySnapshot::className)
        val associationEnrichedEntities = rawEntities.map { owner ->
            owner.copy(
                attributes = owner.attributes.map { attribute ->
                    val association = attribute.associationDetails
                    if (association != null) {
                        val target = entitiesByType[association.relatedEntity]
                            ?: entitiesBySimpleName[association.relatedEntity.substringAfterLast('.')]
                                ?.singleOrNull()
                        return@map attribute.copy(
                            associationDetails = association.copy(
                                relatedTableName = target?.tableName,
                                relatedIdColumnName = target?.idColumnName ?: association.relatedIdColumnName,
                                relatedIdType = target?.idType ?: association.relatedIdType,
                            ),
                        )
                    }
                    if (attribute.embedded) {
                        val embeddable = attribute.embeddedClass?.let { embeddedClass ->
                            entitiesByType[embeddedClass]
                                ?: entitiesBySimpleName[embeddedClass.substringAfterLast('.')]
                                    ?.singleOrNull()
                        }
                        return@map attribute.copy(
                            embeddedAttributeOverrides = attribute.embeddedAttributeOverrides.map { override ->
                                val member = embeddable?.attributes?.singleOrNull {
                                    it.name == override.path.substringBefore('.')
                                }
                                override.copy(
                                    attributeType = member?.let { schemaAttributeType(it.javaType) },
                                    sqlType = override.sqlType ?: member?.sqlType,
                                )
                            },
                            embeddedAssociationOverrides = attribute.embeddedAssociationOverrides.map { override ->
                                val member = embeddable?.attributes?.singleOrNull {
                                    it.name == override.path.substringBefore('.')
                                }
                                override.copy(
                                    relatedEntity = member?.associationDetails?.relatedEntity,
                                    relatedIdType = member?.associationDetails?.relatedIdType,
                                )
                            },
                        )
                    }
                    attribute
                },
            )
        }
        val hierarchyEnrichedEntities = associationEnrichedEntities.map { owner ->
            owner.copy(
                inheritance = resolvedInheritance(owner, associationEnrichedEntities),
            )
        }
        val entities = hierarchyEnrichedEntities.map { owner ->
            val inheritance = inheritedEntityEvidence(owner, hierarchyEnrichedEntities)
            owner.copy(
                inheritedAttributes = inheritance.attributes,
                inheritedTraits = inheritance.traits,
            )
        }
        val repositories = graph.artifacts
            .filter { it.kind == ArtifactKind.REPOSITORY }
            .distinctBy { it.sourceLocator.relativePath }
            .mapNotNull { artifact ->
                val source = content(artifact.sourceLocator.relativePath).orEmpty()
                val kotlin = artifact.sourceLocator.relativePath.endsWith(".kt")
                val parsed = RepositorySourceParser.parse(source, kotlin) ?: return@mapNotNull null
                val entitySimpleName = parsed.entityType
                    .removeSuffix("?")
                    .substringAfterLast('.')
                    .substringBefore('<')
                    .trim()
                val importedEntity = IMPORT_DECLARATION.findAll(source)
                    .map { it.groupValues[1] }
                    .singleOrNull { it.substringAfterLast('.') == entitySimpleName }
                val packageEntity = PACKAGE_DECLARATION.find(source)
                    ?.groupValues?.get(1)
                    ?.let { "$it.$entitySimpleName" }
                val candidates = entities.filter { entity ->
                    entity.className == entitySimpleName &&
                        (
                            entity.qualifiedName == importedEntity ||
                                entity.qualifiedName == packageEntity ||
                                entity.moduleId == artifact.owner.moduleId
                            )
                }
                val entity = when {
                    importedEntity != null -> candidates.singleOrNull {
                        it.qualifiedName == importedEntity
                    }
                    packageEntity != null -> candidates.singleOrNull {
                        it.qualifiedName == packageEntity
                    } ?: candidates.singleOrNull()
                    else -> candidates.singleOrNull()
                } ?: return@mapNotNull null
                val packageName = PACKAGE_DECLARATION.find(source)?.groupValues?.get(1).orEmpty()
                SchemaRepositorySnapshot(
                    artifactId = artifact.id,
                    moduleId = artifact.owner.moduleId,
                    interfaceName = parsed.interfaceName,
                    qualifiedName = if (packageName.isBlank()) {
                        parsed.interfaceName
                    } else {
                        "$packageName.${parsed.interfaceName}"
                    },
                    entityQualifiedName = entity.qualifiedName,
                    idType = parsed.idType,
                    sourceLanguage = if (kotlin) {
                        EntitySourceLanguage.KOTLIN
                    } else {
                        EntitySourceLanguage.JAVA
                    },
                    sourceLocator = artifact.sourceLocator,
                    config = parsed.config,
                    methodEvidence = parsed.methods.map { method ->
                        SchemaRepositoryMethodEvidence(
                            sourceSignature = method.sourceSignature,
                            editable = method.editable,
                            issue = method.issue,
                        )
                    },
                )
            }
            .sortedWith(compareBy(SchemaRepositorySnapshot::moduleId, SchemaRepositorySnapshot::qualifiedName))

        val physicalSchemas = buildPhysicalSchemas(stores, changelogs, ::content)
        val drifts = buildSchemaDrifts(entities, stores, physicalSchemas)
        val findings = (
            buildFindings(entities, stores, changelogs, ::content) +
                drifts.filter { it.severity != SchemaDriftSeverity.INFO }.map { drift ->
                    finding(
                        severity = if (drift.severity == SchemaDriftSeverity.ERROR) {
                            SchemaFindingSeverity.ERROR
                        } else {
                            SchemaFindingSeverity.WARNING
                        },
                        code = "SCHEMA_DRIFT_${drift.kind.name}",
                        message = drift.message,
                        moduleId = drift.moduleId,
                        sourceLocator = drift.entitySourceLocator,
                        entityArtifactId = drift.entityArtifactId,
                    )
                }
            ).distinctBy { listOf(it.code, it.moduleId, it.entityArtifactId, it.message) }
            .sortedWith(compareByDescending<SchemaFinding> { it.severity.ordinal }.thenBy { it.code })
        val modules = (graph.artifacts.map { it.owner.moduleId } + stores.map { it.moduleId })
            .distinct()
            .sorted()
            .map { moduleId ->
                SchemaModuleSnapshot(
                    moduleId = moduleId,
                    projectId = JmixProjectService.getInstance(project).projectIdForModule(
                        modulePrefix(
                            entities.firstOrNull { it.moduleId == moduleId }?.sourceLocator?.relativePath
                                ?: changelogs.firstOrNull { it.moduleId == moduleId }?.relativePath
                                ?: stores.firstOrNull { it.moduleId == moduleId }?.rootChangelogPath.orEmpty(),
                        ),
                    ),
                    entityCount = entities.count { it.moduleId == moduleId },
                    changelogCount = changelogs.count { it.moduleId == moduleId },
                    storeCount = stores.count { it.moduleId == moduleId },
                    findingCount = findings.count { it.moduleId == moduleId },
                )
            }
        return SchemaWorkspaceResponse(
            accepted = true,
            snapshotDigest = graph.snapshotDigest,
            modules = modules,
            stores = stores,
            entities = entities,
            repositories = repositories,
            changelogs = changelogs,
            physicalSchemas = physicalSchemas,
            drifts = drifts,
            findings = findings,
            issues = emptyList(),
        )
    }

    fun previewMigration(request: SchemaMigrationChangeRequest): WorkspaceChangePreviewResponse {
        val proposal = migrationProposal(request)
        if (proposal.changeSet == null) return proposal.rejectedPreview()
        return WorkspaceChangeService.getInstance(project).preview(proposal.changeSet)
    }

    fun prepareMigration(request: SchemaMigrationApplyRequest): PreparedWorkspaceChange {
        val proposal = migrationProposal(request.change)
        val changeSet = proposal.changeSet
        if (changeSet == null) {
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "schema-migration:rejected",
                    label = "Schema migration rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues,
                ),
                baseDir = null,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    internal fun migrationProposal(request: SchemaMigrationChangeRequest): SchemaMigrationProposal {
        val workspace = load()
        val store = workspace.stores.firstOrNull { it.id == request.storeId }
            ?: return SchemaMigrationProposal.failure(
                "JVW-SCHEMA-STORE-MISSING",
                "The selected data store no longer exists. Refresh the schema workspace.",
            )
        val rootPath = store.rootChangelogPath
            ?: return SchemaMigrationProposal.failure(
                "JVW-SCHEMA-ROOT-MISSING",
                "The selected data store has no resolved root Liquibase changelog.",
                store.configurationLocator?.relativePath,
            )
        val rootContent = read(rootPath)
            ?: return SchemaMigrationProposal.failure(
                "JVW-SCHEMA-ROOT-UNREADABLE",
                "The root Liquibase changelog cannot be read.",
                rootPath,
            )
        val duplicate = duplicateChangeSet(request.migration, workspace.changelogs)
        if (duplicate != null) {
            return SchemaMigrationProposal.failure(
                "JVW-SCHEMA-CHANGESET-DUPLICATE",
                "Changeset ${duplicate.first} by ${duplicate.second} already exists in this project.",
            )
        }
        val fileName = safeFileName(request.fileName ?: request.migration.changelogId)
            ?: return SchemaMigrationProposal.failure(
                "JVW-SCHEMA-FILE-NAME-INVALID",
                "Use a migration file name containing only letters, numbers, dots, dashes, and underscores.",
            )
        val directory = store.generatedDirectory
            ?: return SchemaMigrationProposal.failure(
                "JVW-SCHEMA-DESTINATION-MISSING",
                "A source-safe generated changelog directory could not be resolved from the root include chain.",
                rootPath,
            )
        val date = LocalDate.now()
        val datedDirectory = "$directory/${date.year}/${date.monthValue.toString().padStart(2, '0')}"
        val targetPath = "$datedDirectory/${date.dayOfMonth.toString().padStart(2, '0')}-$fileName"
        val migrationContent = MigrationGenerator.generate(
            request.migration.copy(logicalFilePath = request.migration.logicalFilePath ?: classpathPath(targetPath)),
        )
        val changes = mutableListOf(
            WorkspaceFileChange(
                relativePath = targetPath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = migrationContent,
            ),
        )
        if (store.includeMode != SchemaIncludeMode.INCLUDE_ALL) {
            val closing = ROOT_CLOSE.find(rootContent)
                ?: return SchemaMigrationProposal.failure(
                    "JVW-SCHEMA-ROOT-INVALID",
                    "The root changelog has no closing databaseChangeLog element.",
                    rootPath,
                )
            val includePath = classpathPath(targetPath)
            if (!rootContent.contains(includePath)) {
                val newline = if (rootContent.contains("\r\n")) "\r\n" else "\n"
                val insertion = "    <include file=\"$includePath\"/>$newline"
                changes += WorkspaceFileChange(
                    relativePath = rootPath,
                    mode = WorkspaceFileChangeMode.MODIFY,
                    baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(rootContent),
                    edits = listOf(
                        WorkspaceTextEdit(
                            startOffset = closing.range.first,
                            endOffset = closing.range.first,
                            expectedText = "",
                            replacement = insertion,
                        ),
                    ),
                )
            }
        }
        val id = CanonicalDiscoveryJson.sha256(
            listOf(store.id, targetPath, migrationContent).joinToString("\u0000"),
        ).take(24)
        return SchemaMigrationProposal(
            changeSet = WorkspaceChangeSet(
                id = "schema-migration:$id",
                label = "Create ${store.name} Liquibase migration ${request.migration.changelogId}",
                files = changes,
            ),
            issues = emptyList(),
        )
    }

    private fun duplicateChangeSet(
        migration: MigrationModel,
        changelogs: List<SchemaChangelogSnapshot>,
    ): Pair<String, String>? {
        val requested = migration.changes.map { it.id to it.author }.toSet()
        if (requested.size != migration.changes.size) {
            return migration.changes.groupingBy { it.id to it.author }.eachCount()
                .entries.firstOrNull { it.value > 1 }?.key
        }
        changelogs.forEach { changelog ->
            val source = read(changelog.relativePath).orEmpty()
            CHANGESET_TAG.findAll(source).forEach { match ->
                val attributes = tagAttributes(match.groupValues[1])
                val existing = attributes["id"].orEmpty() to attributes["author"].orEmpty()
                if (existing in requested) return existing
            }
        }
        return null
    }

    private fun buildPhysicalSchemas(
        stores: List<SchemaDataStoreSnapshot>,
        changelogs: List<SchemaChangelogSnapshot>,
        content: (String) -> String?,
    ): List<SchemaPhysicalStoreSnapshot> {
        val changelogByPath = changelogs.associateBy(SchemaChangelogSnapshot::relativePath)
        return stores.map { store ->
            val orderedPaths = linkedSetOf<String>()
            fun visit(path: String) {
                if (!orderedPaths.add(path)) return
                val source = content(path) ?: return
                parseIncludes(source).forEach { include ->
                    val resolved = if (include.relativeToChangelogFile) {
                        "${path.substringBeforeLast('/')}/${include.path}".normalizePath()
                    } else {
                        "${resourceRoot(path)}/${include.path.removePrefix("/")}".normalizePath()
                    }
                    if (include.includeAll) {
                        changelogByPath.keys
                            .filter { candidate ->
                                candidate.startsWith("${resolved.removeSuffix("/")}/") &&
                                    candidate.endsWith(".xml", ignoreCase = true)
                            }
                            .sorted()
                            .forEach(::visit)
                    } else {
                        visit(resolved)
                    }
                }
            }
            store.rootChangelogPath?.let(::visit)
            val tables = linkedMapOf<String, MutablePhysicalTable>()
            var complete = true
            orderedPaths.forEach { path ->
                val source = content(path) ?: return@forEach
                val forwardSource = ROLLBACK_BLOCK.replace(source, "")
                if (hasUnclassifiedRawSql(forwardSource)) complete = false
                applyPhysicalOperations(forwardSource, path, tables)
            }
            SchemaPhysicalStoreSnapshot(
                storeId = store.id,
                moduleId = store.moduleId,
                complete = complete,
                changelogPaths = orderedPaths.toList(),
                tables = tables.values
                    .sortedBy(MutablePhysicalTable::name)
                    .map(MutablePhysicalTable::snapshot),
            )
        }
    }

    private fun applyPhysicalOperations(
        source: String,
        sourcePath: String,
        tables: MutableMap<String, MutablePhysicalTable>,
    ) {
        LIQUIBASE_SCHEMA_OPERATION.findAll(source).forEach { match ->
            val blockTag = match.groupValues[1]
            val tag = blockTag.ifBlank { match.groupValues[4] }
            val attributes = tagAttributes(
                if (blockTag.isNotBlank()) match.groupValues[2] else match.groupValues[5],
            )
            val body = if (blockTag.isNotBlank()) match.groupValues[3] else ""
            val tableName = (
                attributes["tableName"]
                    ?: attributes["baseTableName"]
                    ?: attributes["oldTableName"]
                )?.uppercase(Locale.ROOT)
            when (tag) {
                "createTable" -> {
                    val name = attributes["tableName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    val table = MutablePhysicalTable(name)
                    table.sourcePaths += sourcePath
                    parsePhysicalColumns(body).forEach { table.columns[it.name] = it }
                    tables[name] = table
                }
                "dropTable" -> tableName?.let(tables::remove)
                "renameTable" -> {
                    val oldName = attributes["oldTableName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    val newName = attributes["newTableName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    val table = tables.remove(oldName) ?: MutablePhysicalTable(newName)
                    table.name = newName
                    table.sourcePaths += sourcePath
                    tables[newName] = table
                }
                "addColumn" -> {
                    val table = tableName?.let { tables.getOrPut(it) { MutablePhysicalTable(it) } }
                        ?: return@forEach
                    table.sourcePaths += sourcePath
                    parsePhysicalColumns(body).forEach { table.columns[it.name] = it }
                }
                "dropColumn" -> {
                    val column = attributes["columnName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    tableName?.let(tables::get)?.columns?.remove(column)
                }
                "renameColumn" -> {
                    val table = tableName?.let(tables::get) ?: return@forEach
                    val oldName = attributes["oldColumnName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    val newName = attributes["newColumnName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    val column = table.columns.remove(oldName) ?: return@forEach
                    table.columns[newName] = column.copy(
                        name = newName,
                        type = attributes["columnDataType"] ?: column.type,
                    )
                    table.sourcePaths += sourcePath
                }
                "modifyDataType" -> {
                    val table = tableName?.let(tables::get) ?: return@forEach
                    val column = attributes["columnName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    val newType = attributes["newDataType"] ?: return@forEach
                    table.columns[column]?.let { table.columns[column] = it.copy(type = newType) }
                    table.sourcePaths += sourcePath
                }
                "addNotNullConstraint", "dropNotNullConstraint" -> {
                    val table = tableName?.let(tables::get) ?: return@forEach
                    val column = attributes["columnName"]?.uppercase(Locale.ROOT) ?: return@forEach
                    table.columns[column]?.let {
                        table.columns[column] = it.copy(nullable = tag == "dropNotNullConstraint")
                    }
                    table.sourcePaths += sourcePath
                }
                "addUniqueConstraint" -> {
                    val table = tableName?.let { tables.getOrPut(it) { MutablePhysicalTable(it) } }
                        ?: return@forEach
                    val constraint = attributes["constraintName"].orEmpty()
                    val columns = splitColumns(attributes["columnNames"])
                    if (constraint.isNotBlank()) table.uniqueConstraints[constraint] = columns
                    columns.forEach { column ->
                        table.columns[column]?.let { table.columns[column] = it.copy(unique = true) }
                    }
                    table.sourcePaths += sourcePath
                }
                "dropUniqueConstraint" -> {
                    val table = tableName?.let(tables::get) ?: return@forEach
                    val removed = attributes["constraintName"]?.let(table.uniqueConstraints::remove).orEmpty()
                    removed.forEach { column ->
                        val remainsUnique =
                            table.uniqueConstraints.values.any { column in it } ||
                                table.indexes.values.any { it.unique && column in it.columns }
                        table.columns[column]?.let { table.columns[column] = it.copy(unique = remainsUnique) }
                    }
                    table.sourcePaths += sourcePath
                }
                "createIndex" -> {
                    val table = tableName?.let { tables.getOrPut(it) { MutablePhysicalTable(it) } }
                        ?: return@forEach
                    val indexName = attributes["indexName"].orEmpty()
                    val columns = parseIndexColumns(body)
                    val unique = attributes["unique"].equals("true", ignoreCase = true)
                    if (indexName.isNotBlank()) {
                        table.indexes[indexName] = SchemaPhysicalIndexSnapshot(
                            name = indexName,
                            unique = unique,
                            columns = columns,
                        )
                    }
                    if (unique) {
                        columns.forEach { column ->
                            table.columns[column]?.let { table.columns[column] = it.copy(unique = true) }
                        }
                    }
                    table.sourcePaths += sourcePath
                }
                "dropIndex" -> {
                    val table = tableName?.let(tables::get) ?: return@forEach
                    val removed = attributes["indexName"]?.let(table.indexes::remove)
                    removed?.columns.orEmpty().forEach { column ->
                        val remainsUnique =
                            table.uniqueConstraints.values.any { column in it } ||
                                table.indexes.values.any { it.unique && column in it.columns }
                        table.columns[column]?.let { table.columns[column] = it.copy(unique = remainsUnique) }
                    }
                    table.sourcePaths += sourcePath
                }
                "addForeignKeyConstraint" -> {
                    val table = tableName?.let { tables.getOrPut(it) { MutablePhysicalTable(it) } }
                        ?: return@forEach
                    val constraint = attributes["constraintName"].orEmpty()
                    if (constraint.isBlank()) return@forEach
                    table.foreignKeys[constraint] = SchemaPhysicalForeignKeySnapshot(
                        constraintName = constraint,
                        baseColumnNames = attributes["baseColumnNames"].orEmpty().uppercase(Locale.ROOT),
                        referencedTableName = attributes["referencedTableName"].orEmpty().uppercase(Locale.ROOT),
                        referencedColumnNames = attributes["referencedColumnNames"].orEmpty()
                            .ifBlank { "ID" }
                            .uppercase(Locale.ROOT),
                        onDelete = attributes["onDelete"],
                    )
                    table.sourcePaths += sourcePath
                }
                "dropForeignKeyConstraint" -> {
                    val table = tableName?.let(tables::get) ?: return@forEach
                    attributes["constraintName"]?.let(table.foreignKeys::remove)
                    table.sourcePaths += sourcePath
                }
            }
        }
    }

    private fun parsePhysicalColumns(body: String): List<SchemaPhysicalColumnSnapshot> =
        LIQUIBASE_COLUMN.findAll(body).mapNotNull { match ->
            val attributes = tagAttributes(match.groupValues[1])
            val name = attributes["name"]?.uppercase(Locale.ROOT) ?: return@mapNotNull null
            val constraints = LIQUIBASE_CONSTRAINTS.find(match.groupValues[2])
                ?.groupValues
                ?.get(1)
                ?.let(::tagAttributes)
                .orEmpty()
            SchemaPhysicalColumnSnapshot(
                name = name,
                type = attributes["type"].orEmpty(),
                nullable = !constraints["nullable"].equals("false", ignoreCase = true),
                unique = constraints["unique"].equals("true", ignoreCase = true) ||
                    constraints["primaryKey"].equals("true", ignoreCase = true),
                primaryKey = constraints["primaryKey"].equals("true", ignoreCase = true),
            )
        }.toList()

    private fun parseIndexColumns(body: String): List<String> =
        LIQUIBASE_COLUMN.findAll(body)
            .mapNotNull { match -> tagAttributes(match.groupValues[1])["name"] }
            .map { it.uppercase(Locale.ROOT) }
            .toList()

    private fun splitColumns(value: String?): List<String> =
        value.orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
            .map { it.uppercase(Locale.ROOT) }

    private fun retiredColumnName(tableName: String, columnName: String): String {
        val suffix = CanonicalDiscoveryJson.sha256("$tableName\u0000$columnName")
            .take(8)
            .uppercase(Locale.ROOT)
        val readable = columnName
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9_]"), "_")
            .take(17)
        return "ZZR_${suffix}_$readable"
    }

    private fun buildSchemaDrifts(
        entities: List<SchemaEntitySnapshot>,
        stores: List<SchemaDataStoreSnapshot>,
        physicalSchemas: List<SchemaPhysicalStoreSnapshot>,
    ): List<SchemaDriftSnapshot> {
        val databaseType = JmixProjectService.getInstance(project).getConfig()?.databaseType
            ?: org.jmixworkbench.model.DatabaseType.POSTGRES
        val entitiesByName = entities.associateBy(SchemaEntitySnapshot::qualifiedName)
        val entitiesBySimpleName = entities.groupBy(SchemaEntitySnapshot::className)
        val drifts = mutableListOf<SchemaDriftSnapshot>()
        entities.filter {
            it.entityType == EntityType.ENTITY && !it.databaseView
        }.forEach { entity ->
            val store = stores.firstOrNull {
                it.moduleId == entity.moduleId && it.name == entity.storeName
            } ?: return@forEach
            val physicalStore = physicalSchemas.firstOrNull { it.storeId == store.id } ?: return@forEach
            val confidence = if (physicalStore.complete) {
                SchemaDriftConfidence.HIGH
            } else {
                SchemaDriftConfidence.PARTIAL
            }
            val expected = expectedColumns(
                entity,
                entitiesByName,
                entitiesBySimpleName,
                databaseType,
            )
            val expectedByName = expected.associateBy(ExpectedSchemaColumn::name)
            val actualTable = physicalStore.tables.firstOrNull {
                it.name.equals(entity.tableName, ignoreCase = true)
            }
            if (actualTable == null) {
                drifts += drift(
                    entity = entity,
                    storeId = store.id,
                    kind = SchemaDriftKind.TABLE_MISSING,
                    severity = SchemaDriftSeverity.ERROR,
                    safety = SchemaDriftSafety.SAFE,
                    confidence = confidence,
                    message = "${entity.qualifiedName} maps to ${entity.tableName}, but the store changelog chain never creates that table.",
                    suggestion = SchemaDriftSuggestion(
                        changeType = "createTable",
                        tableName = entity.tableName,
                        columns = expected.map { column ->
                            SchemaSuggestedColumn(
                                name = column.name,
                                type = column.type,
                                nullable = column.nullable,
                                unique = column.unique,
                                primaryKey = column.primaryKey,
                            )
                        },
                    ),
                )
                expected.mapNotNull(ExpectedSchemaColumn::foreignKey).forEach { fk ->
                    drifts += foreignKeyDrift(entity, store.id, confidence, fk)
                }
                return@forEach
            }
            val actualByName = actualTable.columns.associateBy(SchemaPhysicalColumnSnapshot::name)
            expected.forEach { expectedColumn ->
                val actual = actualByName[expectedColumn.name]
                if (actual == null) {
                    drifts += drift(
                        entity,
                        store.id,
                        SchemaDriftKind.COLUMN_MISSING,
                        if (expectedColumn.nullable) SchemaDriftSeverity.WARNING else SchemaDriftSeverity.ERROR,
                        SchemaDriftSafety.SAFE,
                        confidence,
                        expectedColumn.name,
                        "${entity.tableName}.${expectedColumn.name} is mapped by ${entity.className} but is absent from Liquibase.",
                        SchemaDriftSuggestion(
                            changeType = "addColumn",
                            tableName = entity.tableName,
                            columnName = expectedColumn.name,
                            columnType = expectedColumn.type,
                            nullable = expectedColumn.nullable,
                        ),
                    )
                    return@forEach
                }
                if (!sameSqlType(expectedColumn.type, actual.type)) {
                    drifts += drift(
                        entity,
                        store.id,
                        SchemaDriftKind.TYPE_MISMATCH,
                        SchemaDriftSeverity.ERROR,
                        SchemaDriftSafety.DATA_CHECK_REQUIRED,
                        confidence,
                        expectedColumn.name,
                        "${entity.tableName}.${expectedColumn.name} is ${actual.type.ifBlank { "unknown" }} in Liquibase but ${expectedColumn.type} in the entity mapping.",
                        SchemaDriftSuggestion(
                            changeType = "modifyColumn",
                            tableName = entity.tableName,
                            columnName = expectedColumn.name,
                            newDataType = expectedColumn.type,
                        ),
                    )
                }
                if (actual.nullable != expectedColumn.nullable) {
                    val tightening = !expectedColumn.nullable
                    drifts += drift(
                        entity,
                        store.id,
                        SchemaDriftKind.NULLABILITY_MISMATCH,
                        if (tightening) SchemaDriftSeverity.ERROR else SchemaDriftSeverity.WARNING,
                        if (tightening) SchemaDriftSafety.DATA_CHECK_REQUIRED else SchemaDriftSafety.SAFE,
                        confidence,
                        expectedColumn.name,
                        "${entity.tableName}.${expectedColumn.name} is " +
                            "${if (actual.nullable) "nullable" else "not null"} in Liquibase but the entity expects " +
                            "${if (expectedColumn.nullable) "nullable" else "not null"}.",
                        SchemaDriftSuggestion(
                            changeType = if (tightening) "addNotNullConstraint" else "dropNotNullConstraint",
                            tableName = entity.tableName,
                            columnName = expectedColumn.name,
                            columnType = expectedColumn.type,
                        ),
                    )
                }
                if (expectedColumn.unique && !actual.unique) {
                    drifts += drift(
                        entity,
                        store.id,
                        SchemaDriftKind.UNIQUE_CONSTRAINT_MISSING,
                        SchemaDriftSeverity.ERROR,
                        SchemaDriftSafety.DATA_CHECK_REQUIRED,
                        confidence,
                        expectedColumn.name,
                        "${entity.tableName}.${expectedColumn.name} is unique in the entity mapping but no unique constraint or index is visible in Liquibase.",
                        SchemaDriftSuggestion(
                            changeType = "addUniqueConstraint",
                            tableName = entity.tableName,
                            constraintName = "UQ_${entity.tableName}_${expectedColumn.name}",
                            columnNames = listOf(expectedColumn.name),
                        ),
                    )
                }
                expectedColumn.foreignKey?.let { fk ->
                    val exists = actualTable.foreignKeys.any { actualFk ->
                        actualFk.baseColumnNames == fk.baseColumnNames &&
                            actualFk.referencedTableName == fk.referencedTableName &&
                            actualFk.referencedColumnNames == fk.referencedColumnNames
                    }
                    if (!exists) drifts += foreignKeyDrift(entity, store.id, confidence, fk)
                }
            }
            actualTable.columns.filterNot { it.name in expectedByName }.forEach { actual ->
                val participatesInOutgoingForeignKey = actualTable.foreignKeys.any { foreignKey ->
                    splitColumns(foreignKey.baseColumnNames).any {
                        it.equals(actual.name, ignoreCase = true)
                    }
                }
                val participatesInIncomingForeignKey = physicalStore.tables.any { table ->
                    table.foreignKeys.any { foreignKey ->
                        foreignKey.referencedTableName.equals(actualTable.name, ignoreCase = true) &&
                            splitColumns(foreignKey.referencedColumnNames).any {
                                it.equals(actual.name, ignoreCase = true)
                            }
                    }
                }
                val retiredName = retiredColumnName(actualTable.name, actual.name)
                val explicitlyProtected = entity.protectedUnmappedColumns.any {
                    it.equals(actual.name, ignoreCase = true)
                }
                val participatesInIndex = actualTable.indexes.any { index ->
                    index.columns.any { it.equals(actual.name, ignoreCase = true) }
                }
                val canQuarantine =
                    confidence == SchemaDriftConfidence.HIGH &&
                        !explicitlyProtected &&
                        !actual.primaryKey &&
                        !actual.unique &&
                        !participatesInOutgoingForeignKey &&
                        !participatesInIncomingForeignKey &&
                        !participatesInIndex &&
                        actualTable.columns.none { it.name.equals(retiredName, ignoreCase = true) }
                drifts += drift(
                    entity,
                    store.id,
                    SchemaDriftKind.UNMAPPED_COLUMN,
                    SchemaDriftSeverity.INFO,
                    if (canQuarantine) {
                        SchemaDriftSafety.DATA_CHECK_REQUIRED
                    } else {
                        SchemaDriftSafety.REVIEW
                    },
                    confidence,
                    actual.name,
                    "${entity.tableName}.${actual.name} exists in Liquibase but is not mapped by " +
                        "${entity.className}; it may be legacy, computed, or intentionally protected." +
                        if (explicitlyProtected) {
                            " The entity explicitly protects this unmapped column."
                        } else {
                            ""
                        } +
                        if (canQuarantine) {
                            " A reversible quarantine rename is available after impact review."
                        } else {
                            ""
                        },
                    if (canQuarantine) {
                        SchemaDriftSuggestion(
                            changeType = "renameColumn",
                            tableName = entity.tableName,
                            columnName = actual.name,
                            columnType = actual.type,
                            newColumnName = retiredName,
                        )
                    } else {
                        null
                    },
                )
            }
        }
        return drifts.sortedWith(
            compareByDescending<SchemaDriftSnapshot> { it.severity.ordinal }
                .thenBy(SchemaDriftSnapshot::moduleId)
                .thenBy(SchemaDriftSnapshot::tableName)
                .thenBy { it.columnName.orEmpty() },
        )
    }

    private fun expectedColumns(
        entity: SchemaEntitySnapshot,
        entitiesByName: Map<String, SchemaEntitySnapshot>,
        entitiesBySimpleName: Map<String, List<SchemaEntitySnapshot>>,
        databaseType: org.jmixworkbench.model.DatabaseType,
    ): List<ExpectedSchemaColumn> {
        val columns = linkedMapOf<String, ExpectedSchemaColumn>()
        columns[entity.idColumnName.uppercase(Locale.ROOT)] = ExpectedSchemaColumn(
            name = entity.idColumnName.uppercase(Locale.ROOT),
            type = idSqlType(entity.idType, databaseType),
            nullable = false,
            unique = true,
            primaryKey = true,
        )
        entity.attributes.filter(SchemaEntityAttributeSnapshot::persistent).forEach { attribute ->
            val name = attribute.columnName.uppercase(Locale.ROOT)
            if (name == entity.idColumnName.uppercase(Locale.ROOT)) return@forEach
            if (!attribute.association) {
                columns[name] = ExpectedSchemaColumn(
                    name = name,
                    type = attributeSqlType(attribute, databaseType),
                    nullable = attribute.nullable,
                    unique = attribute.unique,
                )
                return@forEach
            }
            val association = attribute.associationDetails ?: return@forEach
            if (
                association.crossDataStore ||
                association.associationType == AssociationType.ONE_TO_MANY ||
                association.associationType == AssociationType.MANY_TO_MANY ||
                (
                    association.associationType == AssociationType.ONE_TO_ONE &&
                        !association.mappedBy.isNullOrBlank()
                    )
            ) {
                return@forEach
            }
            val target = entitiesByName[association.relatedEntity]
                ?: entitiesBySimpleName[association.relatedEntity.substringAfterLast('.')]?.singleOrNull()
            val targetTable = target?.tableName ?: association.relatedTableName ?: return@forEach
            val targetIdColumn = target?.idColumnName ?: association.relatedIdColumnName
            val targetIdType = target?.idType ?: association.relatedIdType
            columns[name] = ExpectedSchemaColumn(
                name = name,
                type = idSqlType(targetIdType, databaseType),
                nullable = attribute.nullable,
                unique = attribute.unique || association.associationType == AssociationType.ONE_TO_ONE,
                foreignKey = SchemaPhysicalForeignKeySnapshot(
                    constraintName = "FK_${entity.tableName}_$name",
                    baseColumnNames = name,
                    referencedTableName = targetTable.uppercase(Locale.ROOT),
                    referencedColumnNames = targetIdColumn.uppercase(Locale.ROOT),
                    onDelete = association.onDelete,
                ),
            )
        }
        return columns.values.toList()
    }

    private fun attributeSqlType(
        attribute: SchemaEntityAttributeSnapshot,
        databaseType: org.jmixworkbench.model.DatabaseType,
    ): String {
        val simple = attribute.javaType.substringAfterLast('.').substringBefore('<')
        return when (simple) {
            "String" -> "VARCHAR(${attribute.length ?: 255})"
            "Integer", "int" -> "INT"
            "Long", "long" -> "BIGINT"
            "Double", "double", "Float", "float" -> "DOUBLE"
            "BigDecimal" -> "DECIMAL(${attribute.precision ?: 19},${attribute.scale ?: 2})"
            "Boolean", "boolean" -> "BOOLEAN"
            "Date", "LocalDate" -> "DATE"
            "LocalDateTime", "OffsetDateTime" -> "TIMESTAMP"
            "LocalTime" -> "TIME"
            "UUID" -> if (databaseType == org.jmixworkbench.model.DatabaseType.MSSQL) {
                "UNIQUEIDENTIFIER"
            } else {
                "UUID"
            }
            "byte[]" -> if (databaseType == org.jmixworkbench.model.DatabaseType.POSTGRES) "BYTEA" else "BLOB"
            else -> "VARCHAR(${attribute.length ?: 255})"
        }
    }

    private fun idSqlType(
        idType: IdType,
        databaseType: org.jmixworkbench.model.DatabaseType,
    ): String = when (idType) {
        IdType.UUID -> if (databaseType == org.jmixworkbench.model.DatabaseType.MSSQL) {
            "UNIQUEIDENTIFIER"
        } else {
            "UUID"
        }
        IdType.LONG -> "BIGINT"
        IdType.INTEGER -> "INT"
        IdType.STRING -> "VARCHAR(255)"
        IdType.EMBEDDED -> "COMPOSITE"
    }

    private fun sameSqlType(expected: String, actual: String): Boolean =
        normalizeSqlType(expected) == normalizeSqlType(actual)

    private fun normalizeSqlType(type: String): String {
        val normalized = type.uppercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*,\s*"""), ",")
            .trim()
        return when {
            normalized.startsWith("VARCHAR2") -> normalized.replaceFirst("VARCHAR2", "VARCHAR")
            normalized.startsWith("CHARACTER VARYING") ->
                normalized.replaceFirst("CHARACTER VARYING", "VARCHAR")
            normalized.startsWith("NUMERIC") -> normalized.replaceFirst("NUMERIC", "DECIMAL")
            normalized == "INTEGER" || normalized == "INT4" -> "INT"
            normalized == "INT8" -> "BIGINT"
            normalized == "BOOL" -> "BOOLEAN"
            normalized.startsWith("TIMESTAMP WITHOUT TIME ZONE") -> "TIMESTAMP"
            else -> normalized
        }
    }

    private fun foreignKeyDrift(
        entity: SchemaEntitySnapshot,
        storeId: String,
        confidence: SchemaDriftConfidence,
        foreignKey: SchemaPhysicalForeignKeySnapshot,
    ): SchemaDriftSnapshot = drift(
        entity,
        storeId,
        SchemaDriftKind.FOREIGN_KEY_MISSING,
        SchemaDriftSeverity.ERROR,
        SchemaDriftSafety.SAFE,
        confidence,
        foreignKey.baseColumnNames,
        "${entity.tableName}.${foreignKey.baseColumnNames} references ${foreignKey.referencedTableName}.${foreignKey.referencedColumnNames}, but no matching Liquibase foreign key is visible.",
        SchemaDriftSuggestion(
            changeType = "addForeignKey",
            tableName = entity.tableName,
            constraintName = foreignKey.constraintName,
            baseTableName = entity.tableName,
            baseColumnNames = foreignKey.baseColumnNames,
            referencedTableName = foreignKey.referencedTableName,
            referencedColumnNames = foreignKey.referencedColumnNames,
            onDelete = foreignKey.onDelete,
        ),
    )

    private fun drift(
        entity: SchemaEntitySnapshot,
        storeId: String,
        kind: SchemaDriftKind,
        severity: SchemaDriftSeverity,
        safety: SchemaDriftSafety,
        confidence: SchemaDriftConfidence,
        columnName: String? = null,
        message: String,
        suggestion: SchemaDriftSuggestion?,
    ): SchemaDriftSnapshot {
        val id = CanonicalDiscoveryJson.sha256(
            listOf(storeId, entity.artifactId, kind.name, entity.tableName, columnName.orEmpty(), message)
                .joinToString("\u0000"),
        ).take(24)
        return SchemaDriftSnapshot(
            id = id,
            kind = kind,
            severity = severity,
            safety = safety,
            confidence = confidence,
            moduleId = entity.moduleId,
            storeId = storeId,
            entityArtifactId = entity.artifactId,
            entitySourceLocator = entity.sourceLocator,
            tableName = entity.tableName,
            columnName = columnName,
            message = message,
            suggestion = suggestion,
        )
    }

    private fun buildFindings(
        entities: List<SchemaEntitySnapshot>,
        stores: List<SchemaDataStoreSnapshot>,
        changelogs: List<SchemaChangelogSnapshot>,
        content: (String) -> String?,
    ): List<SchemaFinding> {
        val findings = mutableListOf<SchemaFinding>()
        stores.filter { it.rootChangelogPath == null }.forEach { store ->
            findings += finding(
                SchemaFindingSeverity.ERROR,
                "SCHEMA_STORE_ROOT_MISSING",
                "Data store '${store.name}' is configured but its root changelog cannot be resolved.",
                store.moduleId,
                store.configurationLocator,
            )
        }
        entities.forEach { entity ->
            if (entity.migrationCoverage == SchemaMigrationCoverage.MISSING) {
                findings += finding(
                    SchemaFindingSeverity.WARNING,
                    "SCHEMA_MIGRATION_COVERAGE_MISSING",
                    "${entity.qualifiedName} maps to ${entity.tableName}, but no source changelog operation for that table was indexed.",
                    entity.moduleId,
                    entity.sourceLocator,
                    entity.artifactId,
                )
            }
            if (entity.ddlMode == SchemaDdlMode.DISABLED) {
                findings += finding(
                    SchemaFindingSeverity.INFO,
                    "SCHEMA_DDL_DISABLED",
                    "${entity.qualifiedName} disables generated DDL; schema changes must be maintained explicitly.",
                    entity.moduleId,
                    entity.sourceLocator,
                    entity.artifactId,
                )
            }
            if (entity.tableName.length > ORACLE_IDENTIFIER_LIMIT) {
                findings += finding(
                    SchemaFindingSeverity.WARNING,
                    "SCHEMA_IDENTIFIER_ORACLE_LIMIT",
                    "Table ${entity.tableName} exceeds Oracle's conservative 30-character identifier mode.",
                    entity.moduleId,
                    entity.sourceLocator,
                    entity.artifactId,
                )
            }
            entity.attributes.filter { attribute ->
                BUSINESS_IDENTIFIER.containsMatchIn(attribute.name) && !attribute.unique
            }.forEach { attribute ->
                findings += finding(
                    SchemaFindingSeverity.WARNING,
                    "SCHEMA_BUSINESS_IDENTIFIER_NOT_UNIQUE",
                    "${entity.className}.${attribute.name} looks like a business identifier but has no visible unique mapping.",
                    entity.moduleId,
                    entity.sourceLocator,
                    entity.artifactId,
                )
            }
            entity.attributes.filter {
                it.moneyCandidate && it.javaType.substringAfterLast('.') in setOf("Double", "Float", "double", "float")
            }.forEach { attribute ->
                findings += finding(
                    SchemaFindingSeverity.ERROR,
                    "SCHEMA_UNSAFE_MONEY_TYPE",
                    "${entity.className}.${attribute.name} uses ${attribute.javaType}; financial values should use BigDecimal with explicit precision and scale.",
                    entity.moduleId,
                    entity.sourceLocator,
                    entity.artifactId,
                )
            }
        }
        changelogs.filter(SchemaChangelogSnapshot::containsRawSql).forEach { changelog ->
            val source = content(changelog.relativePath).orEmpty()
            if (
                hasUnclassifiedRawSql(source) &&
                RAW_SQL_WITHOUT_DBMS.containsMatchIn(source)
            ) {
                findings += finding(
                    SchemaFindingSeverity.WARNING,
                    "SCHEMA_RAW_SQL_NOT_SCOPED",
                    "${changelog.relativePath} contains raw SQL without an explicit dbms scope; verify portability and rollback.",
                    changelog.moduleId,
                    changelog.sourceLocator,
                )
            }
        }
        stores.filter { it.includeMode == SchemaIncludeMode.EXPLICIT }.forEach { store ->
            val root = store.rootChangelogPath ?: return@forEach
            val resourceRoot = resourceRoot(root)
            store.includeTargets.forEach { target ->
                if (target.includeAll) return@forEach
                val resolved = if (target.relativeToChangelogFile) {
                    "${root.substringBeforeLast('/')}/${target.path}".normalizePath()
                } else {
                    "$resourceRoot/${target.path.removePrefix("/")}".normalizePath()
                }
                if (read(resolved) == null && !target.path.startsWith("io/jmix/")) {
                    findings += finding(
                        SchemaFindingSeverity.WARNING,
                        "SCHEMA_INCLUDE_TARGET_MISSING",
                        "Root changelog include '${target.path}' does not resolve inside the open project.",
                        store.moduleId,
                        store.rootLocator,
                    )
                }
            }
        }
        return findings.sortedWith(compareByDescending<SchemaFinding> { it.severity.ordinal }.thenBy { it.code })
    }

    private fun hasUnclassifiedRawSql(source: String): Boolean {
        if (!RAW_SQL.containsMatchIn(source)) return false
        val blocks = RAW_SQL_BLOCK.findAll(source).map { it.groupValues[1] }.toList()
        if (blocks.isEmpty()) return true
        return blocks.any { body ->
            val sql = body.trim()
            if (!sql.startsWith(DATA_ONLY_BACKFILL_MARKER)) {
                true
            } else {
                val statement = sql.removePrefix(DATA_ONLY_BACKFILL_MARKER).trimStart()
                !statement.startsWith("UPDATE ", ignoreCase = true) ||
                    RAW_SQL_SCHEMA_OPERATION.containsMatchIn(statement)
            }
        }
    }

    private fun entityAttributes(
        artifacts: List<ArtifactSnapshot>,
        relationships: List<org.jmixworkbench.discovery.model.ArtifactRelationship>,
    ): Map<String, List<ArtifactSnapshot>> {
        val byId = artifacts.associateBy(ArtifactSnapshot::id)
        return relationships
            .filter { it.type == org.jmixworkbench.discovery.model.RelationshipType.DECLARES }
            .mapNotNull { relationship ->
                val target = relationship.targetArtifactId?.let(byId::get)
                if (target?.kind == ArtifactKind.ENTITY_ATTRIBUTE) relationship.sourceArtifactId to target else null
            }
            .groupBy({ it.first }, { it.second })
    }

    private fun resolveConfiguredRoot(
        configuredPath: String,
        candidates: List<ArtifactSnapshot>,
    ): ArtifactSnapshot? {
        if (configuredPath.isBlank() || configuredPath.contains("\${")) return null
        return candidates.firstOrNull {
            val path = it.sourceLocator.relativePath
            path == configuredPath ||
                path.endsWith("/src/main/resources/$configuredPath") ||
                path.endsWith("/$configuredPath")
        }
    }

    private fun recommendedDirectory(rootPath: String, source: String): String? {
        val root = resourceRoot(rootPath)
        val includeAll = parseIncludes(source).firstOrNull(SchemaIncludeTarget::includeAll)
        if (includeAll != null) {
            val path = includeAll.path.trim().removePrefix("/")
            return if (includeAll.relativeToChangelogFile) {
                "${rootPath.substringBeforeLast('/')}/$path".normalizePath()
            } else {
                "$root/$path".normalizePath()
            }
        }
        return "${rootPath.substringBeforeLast('/')}/changelog".normalizePath()
    }

    private fun parseIncludes(source: String): List<SchemaIncludeTarget> =
        buildList {
            INCLUDE_TAG.findAll(source).forEach { match ->
                val attributes = tagAttributes(match.groupValues[1])
                val path = attributes["file"].orEmpty()
                if (path.isBlank()) return@forEach
                add(
                    SchemaIncludeTarget(
                        path = path,
                        includeAll = false,
                        relativeToChangelogFile = attributes["relativeToChangelogFile"]
                            .equals("true", ignoreCase = true),
                    ),
                )
            }
            INCLUDE_ALL_TAG.findAll(source).forEach { match ->
                val attributes = tagAttributes(match.groupValues[1])
                val path = attributes["path"].orEmpty()
                if (path.isBlank()) return@forEach
                add(
                    SchemaIncludeTarget(
                        path = path,
                        includeAll = true,
                        relativeToChangelogFile = attributes["relativeToChangelogFile"]
                            .equals("true", ignoreCase = true),
                    ),
                )
            }
        }.distinct()

    private fun includeMode(source: String): SchemaIncludeMode = when {
        INCLUDE_ALL_TAG.containsMatchIn(source) -> SchemaIncludeMode.INCLUDE_ALL
        INCLUDE_TAG.containsMatchIn(source) -> SchemaIncludeMode.EXPLICIT
        source.isBlank() -> SchemaIncludeMode.MISSING
        else -> SchemaIncludeMode.DIRECT
    }

    private fun hasIncludes(source: String): Boolean =
        INCLUDE_TAG.containsMatchIn(source) || INCLUDE_ALL_TAG.containsMatchIn(source)

    private fun tagAttributes(source: String): Map<String, String> =
        XML_ATTRIBUTE.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }

    private fun tableName(entity: ArtifactSnapshot, source: String): String =
        TABLE_ANNOTATION.find(source)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
            ?: entity.displayName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase(Locale.ROOT)

    private fun entityType(source: String): EntityType = when {
        MAPPED_SUPERCLASS_ANNOTATION.containsMatchIn(source) -> EntityType.MAPPED_SUPERCLASS
        EMBEDDABLE_ANNOTATION.containsMatchIn(source) -> EntityType.EMBEDDABLE
        JPA_ENTITY_ANNOTATION.containsMatchIn(source) -> EntityType.ENTITY
        else -> EntityType.DTO
    }

    private fun idMapping(source: String): Pair<IdType, String> {
        val declaration = sourceFields(source).firstOrNull { field ->
            ID_ANNOTATION.containsMatchIn(field.declaration)
        } ?: return IdType.UUID to "ID"
        val typeName = declaration.type.substringAfterLast('.')
        val type = when (typeName) {
            "UUID" -> IdType.UUID
            "Long", "long" -> IdType.LONG
            "Integer", "Int", "int" -> IdType.INTEGER
            "String" -> IdType.STRING
            else -> IdType.EMBEDDED
        }
        val column = COLUMN_ANNOTATION.find(declaration.declaration)?.groupValues?.get(1)
            ?.takeIf(String::isNotBlank)
            ?: declaration.name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .uppercase(Locale.ROOT)
        return type to column
    }

    private fun modulePrefix(relativePath: String): String {
        val marker = "/src/main/"
        return when {
            marker in relativePath -> relativePath.substringBefore(marker)
            relativePath.startsWith("src/main/") -> ""
            else -> ""
        }
    }

    private fun columnName(source: String, fieldName: String): String {
        val declaration = sourceField(source, fieldName)?.declaration.orEmpty()
        return COLUMN_ANNOTATION.find(declaration)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
            ?: fieldName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase(Locale.ROOT)
    }

    private fun fieldNullable(source: String, fieldName: String): Boolean {
        val declaration = fieldDeclaration(source, fieldName)
        return !NULLABLE_FALSE.containsMatchIn(declaration)
    }

    private fun fieldUnique(source: String, fieldName: String): Boolean {
        val declaration = fieldDeclaration(source, fieldName)
        if (UNIQUE_TRUE.containsMatchIn(declaration)) return true
        return Regex("""(?is)(uniqueConstraints|indexes)\s*=\s*\{?[^}]*\b${Regex.escape(columnName(source, fieldName))}\b""")
            .containsMatchIn(source)
    }

    private fun fieldIntegerArgument(source: String, fieldName: String, argument: String): Int? {
        val declaration = fieldDeclaration(source, fieldName)
        return Regex("""\b${Regex.escape(argument)}\s*=\s*(\d+)\b""")
            .find(declaration)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private fun fieldDeclaration(source: String, fieldName: String): String {
        return sourceField(source, fieldName)?.declaration.orEmpty()
    }

    private fun attributeMetadata(
        source: String,
        fieldName: String,
        declaration: String,
    ): ParsedAttributeMetadata {
        fun has(name: String): Boolean = Regex(
            """@\s*(?:[\w.]+\.)?${Regex.escape(name)}\b""",
        ).containsMatchIn(declaration)
        fun firstString(arguments: String?): String? = arguments
            ?.let { STRING_LITERAL.find(it)?.groupValues?.get(1) }
            ?.let(::unescapeAnnotationString)
        fun argument(arguments: String?, name: String): String? = arguments
            ?.let {
                Regex("""(?s)\b${Regex.escape(name)}\s*=\s*("(?:\\.|[^"\\])*"|[^,\])}]+)""")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }
        fun stringArgument(arguments: String?, name: String): String? =
            argument(arguments, name)
                ?.takeIf { it.startsWith('"') && it.endsWith('"') }
                ?.removeSurrounding("\"")
                ?.let(::unescapeAnnotationString)

        val validations = ValidationType.entries.mapNotNull { type ->
            if (!has(type.annotation)) return@mapNotNull null
            val arguments = annotationArguments(declaration, type.annotation)
            val values = when (type) {
                ValidationType.SIZE ->
                    argument(arguments, "min") to argument(arguments, "max")
                ValidationType.DIGITS ->
                    argument(arguments, "integer") to argument(arguments, "fraction")
                ValidationType.PATTERN ->
                    stringArgument(arguments, "regexp") to null
                ValidationType.DECIMAL_MIN, ValidationType.DECIMAL_MAX ->
                    (stringArgument(arguments, "value") ?: firstString(arguments)) to null
                ValidationType.MIN, ValidationType.MAX ->
                    (argument(arguments, "value") ?: arguments?.trim()?.takeIf(String::isNotBlank)) to null
                else -> null to null
            }
            val groups = arguments
                ?.let { VALIDATION_GROUPS.find(it)?.groupValues?.get(1) }
                ?.let { body ->
                    CLASS_LITERAL.findAll(body)
                        .map { it.groupValues[1] }
                        .distinct()
                        .toMutableList()
                }
                ?: mutableListOf()
            ValidationModel(
                type = type,
                value = values.first,
                value2 = values.second,
                message = stringArgument(arguments, "message"),
                groups = groups,
            )
        }
        val managed = MANAGED_ATTRIBUTE_ANNOTATIONS +
            ValidationType.entries.map(ValidationType::annotation)
        val annotationNames = ANNOTATION_NAME.findAll(declaration)
            .map { it.groupValues[1].substringAfterLast('.') }
            .filterNot { it in managed }
            .distinct()
            .sorted()
            .toList()
        val dependsOn = annotationArguments(declaration, "DependsOnProperties")
            ?.let { arguments ->
                STRING_LITERAL.findAll(arguments)
                    .map { unescapeAnnotationString(it.groupValues[1]) }
                    .distinct()
                    .toList()
            }
            .orEmpty()
        val columnArguments = annotationArguments(declaration, "Column")
        return ParsedAttributeMetadata(
            comment = firstString(annotationArguments(declaration, "Comment")),
            systemLevel = has("SystemLevel"),
            lob = has("Lob"),
            jmixProperty = has("JmixProperty"),
            dependsOnProperties = dependsOn,
            propertyDatatype = firstString(annotationArguments(declaration, "PropertyDatatype")),
            validations = validations,
            readOnly = Regex("""\bval\s+${Regex.escape(fieldName)}\b""").containsMatchIn(declaration),
            unmanagedAnnotations = annotationNames,
            sqlType = stringArgument(columnArguments, "columnDefinition"),
        )
    }

    private fun unescapeAnnotationString(value: String): String =
        value.replace("\\\"", "\"")
            .replace("\\\\", "\\")

    private fun associationSnapshot(
        source: String,
        fieldName: String,
        fallbackType: String,
        ownerQualifiedName: String,
    ): SchemaAssociationSnapshot? {
        val field = sourceField(source, fieldName)
            ?: return null
        val declaration = field.declaration
        val relationName = RELATION_ANNOTATION.find(declaration)?.groupValues?.get(1)
        val dependsOn = DEPENDS_ON_PROPERTIES.find(declaration)?.groupValues?.get(1)
        val crossDataStore = relationName == null &&
            TRANSIENT_ANNOTATION.containsMatchIn(declaration) &&
            JMIX_PROPERTY_ANNOTATION.containsMatchIn(declaration) &&
            !dependsOn.isNullOrBlank()
        if (relationName == null && !crossDataStore) return null

        val rawType = field.type.ifBlank { fallbackType }
        val targetType = relationshipTargetType(rawType)
        if (targetType.substringAfterLast('.') in SCALAR_TYPES) return null
        val relatedEntity = resolveJavaType(source, ownerQualifiedName, targetType)
        val associationType = when (relationName) {
            "OneToMany" -> AssociationType.ONE_TO_MANY
            "ManyToMany" -> AssociationType.MANY_TO_MANY
            "OneToOne" -> AssociationType.ONE_TO_ONE
            else -> AssociationType.MANY_TO_ONE
        }
        val relationArguments = relationName?.let { annotationArguments(declaration, it) }.orEmpty()
        val localIdAttribute = dependsOn?.trim()?.takeIf(String::isNotBlank)
        val joinColumnArguments = if (
            associationType in setOf(AssociationType.MANY_TO_ONE, AssociationType.ONE_TO_ONE)
        ) {
            annotationArguments(declaration, "JoinColumn")
        } else {
            null
        }
        val joinColumn = if (crossDataStore) {
            localIdAttribute?.let { columnName(source, it) }
        } else {
            stringArgument(joinColumnArguments.orEmpty(), "name")
        }
        val referencedColumn = stringArgument(
            joinColumnArguments.orEmpty(),
            "referencedColumnName",
        ) ?: "ID"
        val joinTable = if (associationType == AssociationType.MANY_TO_MANY) {
            joinTable(declaration)
        } else {
            null
        }
        val cascades = CASCADE_VALUE.findAll(relationArguments)
            .mapNotNull { match ->
                runCatching { CascadeType.valueOf(match.groupValues[1].uppercase(Locale.ROOT)) }
                    .getOrNull()
            }
            .distinct()
            .toList()
        val fetch = when {
            FETCH_EAGER.containsMatchIn(relationArguments) -> FetchType.EAGER
            FETCH_LAZY.containsMatchIn(relationArguments) -> FetchType.LAZY
            associationType in setOf(AssociationType.MANY_TO_ONE, AssociationType.ONE_TO_ONE) ->
                FetchType.EAGER
            else -> FetchType.LAZY
        }
        val onDelete = ON_DELETE_POLICY.find(declaration)?.groupValues?.get(1)

        return SchemaAssociationSnapshot(
            associationType = associationType,
            relatedEntity = relatedEntity,
            relatedIdColumnName = referencedColumn,
            localIdAttributeName = localIdAttribute,
            mappedBy = stringArgument(relationArguments, "mappedBy"),
            joinColumnName = joinColumn,
            joinTable = joinTable,
            cascade = cascades,
            fetch = fetch,
            collectionType = if (rawType.substringBefore('<').substringAfterLast('.') == "Set") {
                AssociationCollectionType.SET
            } else {
                AssociationCollectionType.LIST
            },
            crossDataStore = crossDataStore,
            orphanRemoval = BOOLEAN_TRUE_ARGUMENT.find(relationArguments)
                ?.groupValues
                ?.get(1)
                ?.equals("orphanRemoval", ignoreCase = true) == true,
            composition = COMPOSITION_ANNOTATION.containsMatchIn(declaration),
            onDelete = onDelete,
        )
    }

    private fun relationshipTargetType(javaType: String): String {
        val trimmed = javaType.trim().removeSuffix("?")
        val generic = trimmed.substringAfter('<', "").substringBeforeLast('>', "")
        if (generic.isBlank()) return trimmed
        return generic
            .substringAfter("? extends ", generic)
            .substringAfter("? super ", generic)
            .trim()
            .removeSuffix("?")
            .ifBlank { trimmed }
    }

    private fun schemaAttributeType(javaType: String): AttributeType? {
        val simple = javaType.trim()
            .removeSuffix("?")
            .substringAfterLast('.')
            .substringBefore('<')
        return when (simple) {
            "String" -> AttributeType.STRING
            "Character", "Char", "char" -> AttributeType.CHARACTER
            "Integer", "Int", "int" -> AttributeType.INTEGER
            "Long", "long" -> AttributeType.LONG
            "Double", "double" -> AttributeType.DOUBLE
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
            "FileRef" -> AttributeType.FILE_REF
            "ByteArray", "byte[]" -> AttributeType.BYTE_ARRAY
            else -> null
        }
    }

    private fun resolveJavaType(
        source: String,
        ownerQualifiedName: String,
        rawType: String,
    ): String {
        val cleaned = rawType.trim().removeSuffix("?")
        if ('.' in cleaned) return cleaned
        val imported = IMPORT_DECLARATION.findAll(source)
            .firstOrNull { it.groupValues[1].substringAfterLast('.') == cleaned }
            ?.groupValues
            ?.get(1)
        if (imported != null) return imported
        val ownerPackage = ownerQualifiedName.substringBeforeLast('.', "")
        return if (ownerPackage.isBlank()) cleaned else "$ownerPackage.$cleaned"
    }

    private fun annotationArguments(declaration: String, annotationName: String): String? =
        annotationArgumentBodies(declaration, annotationName).firstOrNull()

    /**
     * Returns balanced annotation argument bodies. Regex-only parsing truncates
     * Jakarta container annotations at the first nested `)`, which made
     * @AttributeOverrides and @AssociationOverrides impossible to round-trip.
     */
    private fun annotationArgumentBodies(
        declaration: String,
        annotationName: String,
    ): List<String> = buildList {
        val start = Regex(
            """@\s*(?:[\w.]+\.)?${Regex.escape(annotationName)}\b\s*\(""",
        )
        start.findAll(declaration).forEach { match ->
            val bodyStart = match.range.last + 1
            var depth = 1
            var inString = false
            var escaped = false
            var cursor = bodyStart
            while (cursor < declaration.length && depth > 0) {
                val character = declaration[cursor]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                } else {
                    when (character) {
                        '"' -> inString = true
                        '(' -> depth += 1
                        ')' -> depth -= 1
                    }
                }
                cursor += 1
            }
            if (depth == 0) add(declaration.substring(bodyStart, cursor - 1))
        }
    }

    private fun stringArgument(arguments: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*"([^"]*)"""")
            .find(arguments)
            ?.groupValues
            ?.get(1)
            ?.takeIf(String::isNotBlank)

    private fun booleanArgument(arguments: String, name: String): Boolean? =
        Regex("""\b${Regex.escape(name)}\s*=\s*(true|false)\b""", RegexOption.IGNORE_CASE)
            .find(arguments)
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrictOrNull()

    private fun integerArgument(arguments: String, name: String): Int? =
        Regex("""\b${Regex.escape(name)}\s*=\s*(\d+)\b""")
            .find(arguments)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun firstStringArgument(arguments: String): String? =
        STRING_LITERAL.find(arguments)
            ?.groupValues
            ?.get(1)
            ?.let(::unescapeAnnotationString)

    private fun isEmbedded(declaration: String): Boolean =
        Regex("""@\s*(?:[\w.]+\.)?Embedded(?:Id)?\b""").containsMatchIn(declaration)

    private fun embeddedAttributeOverrides(
        declaration: String,
    ): List<EmbeddedAttributeOverride> =
        annotationArgumentBodies(declaration, "AttributeOverride")
            .mapNotNull { arguments ->
                val path = stringArgument(arguments, "name") ?: return@mapNotNull null
                val column = annotationArguments(arguments, "Column") ?: return@mapNotNull null
                val columnName = stringArgument(column, "name") ?: return@mapNotNull null
                EmbeddedAttributeOverride(
                    path = path,
                    columnName = columnName,
                    sqlType = stringArgument(column, "columnDefinition"),
                    nullable = booleanArgument(column, "nullable"),
                    unique = booleanArgument(column, "unique"),
                    length = integerArgument(column, "length"),
                    precision = integerArgument(column, "precision"),
                    scale = integerArgument(column, "scale"),
                    insertable = booleanArgument(column, "insertable"),
                    updatable = booleanArgument(column, "updatable"),
                    columnDefinition = stringArgument(column, "columnDefinition"),
                )
            }
            .distinctBy(EmbeddedAttributeOverride::path)

    private fun embeddedAssociationOverrides(
        declaration: String,
    ): List<EmbeddedAssociationOverride> =
        annotationArgumentBodies(declaration, "AssociationOverride")
            .mapNotNull { arguments ->
                val path = stringArgument(arguments, "name") ?: return@mapNotNull null
                val joinColumns = annotationArgumentBodies(arguments, "JoinColumn")
                    .mapNotNull { column ->
                        val name = stringArgument(column, "name") ?: return@mapNotNull null
                        AssociationJoinColumn(
                            name = name,
                            referencedColumnName = stringArgument(column, "referencedColumnName").orEmpty(),
                            nullable = booleanArgument(column, "nullable"),
                            insertable = booleanArgument(column, "insertable") ?: true,
                            updatable = booleanArgument(column, "updatable") ?: true,
                        )
                    }
                    .toMutableList()
                if (joinColumns.isEmpty()) return@mapNotNull null
                EmbeddedAssociationOverride(path, joinColumns)
            }
            .distinctBy(EmbeddedAssociationOverride::path)

    private fun inheritanceSnapshot(source: String): InheritanceConfig? {
        fun has(name: String): Boolean = Regex(
            """@\s*(?:[\w.]+\.)?${Regex.escape(name)}\b""",
        ).containsMatchIn(source)

        val hasInheritance = has("Inheritance")
        val hasDiscriminatorColumn = has("DiscriminatorColumn")
        val hasDiscriminatorValue = has("DiscriminatorValue")
        val hasPrimaryKeyJoin = has("PrimaryKeyJoinColumn")
        if (
            !hasInheritance &&
            !hasDiscriminatorColumn &&
            !hasDiscriminatorValue &&
            !hasPrimaryKeyJoin
        ) {
            return null
        }
        val inheritanceArguments = annotationArguments(source, "Inheritance").orEmpty()
        val strategyName = Regex("""InheritanceType\s*\.\s*(SINGLE_TABLE|JOINED|TABLE_PER_CLASS)""")
            .find(inheritanceArguments)
            ?.groupValues
            ?.get(1)
            ?: "SINGLE_TABLE"
        val discriminatorArguments = annotationArguments(source, "DiscriminatorColumn").orEmpty()
        val discriminatorType = Regex("""DiscriminatorType\s*\.\s*(STRING|CHAR|INTEGER)""")
            .find(discriminatorArguments)
            ?.groupValues
            ?.get(1)
            ?: "STRING"
        val discriminatorValueArguments = annotationArguments(source, "DiscriminatorValue").orEmpty()
        val primaryKeyJoinArguments = annotationArguments(source, "PrimaryKeyJoinColumn").orEmpty()
        return InheritanceConfig(
            role = if (hasInheritance) InheritanceRole.ROOT else InheritanceRole.SUBTYPE,
            strategy = InheritanceStrategy.valueOf(strategyName),
            discriminatorColumn = stringArgument(discriminatorArguments, "name"),
            discriminatorType = discriminatorType,
            discriminatorLength = integerArgument(discriminatorArguments, "length"),
            discriminatorValue = firstStringArgument(discriminatorValueArguments),
            primaryKeyJoinColumnName = stringArgument(primaryKeyJoinArguments, "name"),
            primaryKeyJoinReferencedColumnName = stringArgument(
                primaryKeyJoinArguments,
                "referencedColumnName",
            ),
        )
    }

    private fun resolvedInheritance(
        owner: SchemaEntitySnapshot,
        entities: List<SchemaEntitySnapshot>,
    ): InheritanceConfig? {
        if (owner.inheritance?.role == InheritanceRole.ROOT) return owner.inheritance
        val directParent = owner.extendsClass
            ?.let { resolveEntityReference(owner, it, entities) }
            ?: return owner.inheritance
        var root = directParent
        val seen = mutableSetOf(owner.qualifiedName)
        var depth = 0
        while (
            root.inheritance?.role != InheritanceRole.ROOT &&
            !root.extendsClass.isNullOrBlank() &&
            seen.add(root.qualifiedName) &&
            depth < MAX_INHERITANCE_DEPTH
        ) {
            root = resolveEntityReference(root, root.extendsClass.orEmpty(), entities) ?: break
            depth += 1
        }
        val rootConfig = root.inheritance?.takeIf { it.role == InheritanceRole.ROOT }
            ?: return owner.inheritance
        val local = owner.inheritance ?: InheritanceConfig(role = InheritanceRole.SUBTYPE)
        return local.copy(
            role = InheritanceRole.SUBTYPE,
            strategy = rootConfig.strategy,
            discriminatorType = rootConfig.discriminatorType,
            parentTableName = if (rootConfig.strategy == InheritanceStrategy.SINGLE_TABLE) {
                root.tableName
            } else {
                directParent.tableName
            },
            parentIdColumnName = directParent.idColumnName,
        )
    }

    private fun joinTable(declaration: String): JoinTableConfig? {
        val name = JOIN_TABLE_NAME.find(declaration)?.groupValues?.get(1) ?: return null
        val joinColumn = JOIN_TABLE_OWNER_COLUMN.find(declaration)?.groupValues?.get(1) ?: return null
        val inverseColumn = JOIN_TABLE_INVERSE_COLUMN.find(declaration)?.groupValues?.get(1) ?: return null
        return JoinTableConfig(name, joinColumn, inverseColumn)
    }

    private fun sourceFields(source: String): List<ParsedSourceField> = buildList {
        JAVA_FIELD_DECLARATION.findAll(source).forEach { match ->
            val declarationStart = annotationBlockStart(source, match.range.first)
            add(
                ParsedSourceField(
                    type = match.groupValues[1].trim(),
                    name = match.groupValues[2],
                    declaration = source.substring(declarationStart, match.range.last + 1),
                ),
            )
        }
        KOTLIN_PROPERTY_DECLARATION.findAll(source).forEach { match ->
            val declarationStart = annotationBlockStart(source, match.range.first)
            add(
                ParsedSourceField(
                    type = match.groupValues[2].trim().removeSuffix("?").trim(),
                    name = match.groupValues[1],
                    declaration = source.substring(declarationStart, match.range.last + 1),
                ),
            )
        }
    }.distinctBy(ParsedSourceField::name)

    private fun sourceField(source: String, fieldName: String): ParsedSourceField? =
        sourceFields(source).firstOrNull { it.name == fieldName }

    private fun annotationBlockStart(source: String, fieldStart: Int): Int {
        var cursor = source.lastIndexOf('\n', (fieldStart - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        var start = cursor
        var parenthesisDepth = 0
        while (cursor > 0) {
            val previousLineBreak = source.lastIndexOf('\n', (cursor - 2).coerceAtLeast(0))
            val previousStart = if (previousLineBreak < 0) 0 else previousLineBreak + 1
            val line = source.substring(previousStart, cursor).trim()
            if (line.isBlank()) break
            val nextDepth = parenthesisDepth +
                line.count { it == ')' } -
                line.count { it == '(' }
            if (!line.startsWith('@') && parenthesisDepth == 0 && nextDepth <= 0) break
            start = previousStart
            parenthesisDepth = nextDepth.coerceAtLeast(0)
            cursor = previousStart
        }
        return start
    }

    private fun ddlMode(source: String): SchemaDdlMode {
        val annotation = DDL_GENERATION.find(source)?.groupValues?.get(1)
        return when {
            annotation == null -> SchemaDdlMode.CREATE_AND_DROP
            annotation.contains("DISABLED") -> SchemaDdlMode.DISABLED
            annotation.contains("CREATE_ONLY") -> SchemaDdlMode.CREATE_ONLY
            else -> SchemaDdlMode.CREATE_AND_DROP
        }
    }

    private fun ddlUnmappedColumns(source: String): List<String> {
        val arguments = DDL_GENERATION.find(source)?.groupValues?.get(1).orEmpty()
        val body = Regex("""(?s)\bunmappedColumns\s*=\s*[\[{]([^}\]]*)[}\]]""")
            .find(arguments)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return Regex(""""((?:\\.|[^"\\])*)"""")
            .findAll(body)
            .map { it.groupValues[1].uppercase(Locale.ROOT) }
            .distinct()
            .sorted()
            .toList()
    }

    private fun safeFileName(raw: String): String? {
        val normalized = raw.trim().removeSuffix(".xml")
        if (normalized.isBlank() || normalized.length > 120 || !SAFE_FILE_NAME.matches(normalized)) return null
        return "$normalized.xml"
    }

    private fun entityTraits(source: String, idType: IdType): List<TraitType> {
        fun annotation(name: String): Boolean = Regex(
            """@\s*(?:[\w.]+\.)?${Regex.escape(name)}\b""",
        ).containsMatchIn(source)

        val version = annotation("Version")
        val createdBy = annotation("CreatedBy")
        val createdDate = annotation("CreatedDate")
        val updatedBy = annotation("LastModifiedBy")
        val updatedDate = annotation("LastModifiedDate")
        val completeAudit = createdBy && createdDate && updatedBy && updatedDate
        return buildList {
            if (idType == IdType.UUID && version && completeAudit) {
                add(TraitType.STANDARD_ENTITY)
            } else {
                if (version) add(TraitType.HAS_VERSION)
                if (completeAudit) {
                    add(TraitType.AUDITABLE)
                } else {
                    if (createdBy) add(TraitType.CREATED_BY)
                    if (createdDate) add(TraitType.CREATED_DATE)
                    if (updatedBy) add(TraitType.UPDATED_BY)
                    if (updatedDate) add(TraitType.UPDATED_DATE)
                }
            }
            if (
                idType != IdType.UUID &&
                sourceFields(source).any { field ->
                    field.name == "uuid" &&
                        Regex("""@\s*(?:[\w.]+\.)?JmixGeneratedValue\b""")
                            .containsMatchIn(field.declaration)
                }
            ) {
                add(TraitType.UUID_TRAIT)
            }
            if (annotation("DeletedDate") || annotation("DeletedBy")) {
                add(TraitType.SOFT_DELETE)
            }
            if (annotation("TenantId")) {
                add(TraitType.HAS_TENANT_ID)
            }
        }.distinct()
    }

    private fun lifecycleCallbacks(source: String): List<LifecycleCallback> =
        LifecycleCallback.entries.filter { callback ->
            Regex(
                """@\s*(?:[\w.]+\.)?${Regex.escape(callback.annotation.removePrefix("@"))}\b""",
            ).containsMatchIn(source)
        }

    private fun entityListeners(source: String): List<String> =
        ENTITY_LISTENERS.findAll(source)
            .flatMap { match -> CLASS_LITERAL.findAll(match.groupValues[1]) }
            .map { match -> qualifyDeclaredType(source, match.groupValues[1]) }
            .distinct()
            .toList()

    private fun entityHierarchy(source: String, className: String): ParsedEntityHierarchy {
        val escapedName = Regex.escape(className)
        val javaHeader = Regex(
            """(?s)\b(?:class|record)\s+$escapedName\b\s*(?:<[^>{}]*>)?\s*""" +
                """(?:extends\s+([^{}]*?))?(?:\s+implements\s+([^{}]*?))?\s*\{""",
        ).find(source)
        if (javaHeader != null) {
            val parent = javaHeader.groupValues[1]
                .trim()
                .takeIf(String::isNotBlank)
                ?.let { qualifyDeclaredType(source, it) }
            val interfaces = splitTopLevel(javaHeader.groupValues[2])
                .map { qualifyDeclaredType(source, it) }
            return ParsedEntityHierarchy(parent, interfaces)
        }

        val declaration = Regex(
            """\b(?:(?:data|open|abstract|sealed|value|annotation)\s+)*class\s+$escapedName\b""",
        ).find(source) ?: return ParsedEntityHierarchy()
        val openingBrace = source.indexOf('{', declaration.range.last + 1)
        if (openingBrace < 0) return ParsedEntityHierarchy()
        val header = source.substring(declaration.range.last + 1, openingBrace)
        val colon = topLevelColon(header)
        if (colon < 0) return ParsedEntityHierarchy()
        val supertypes = splitTopLevel(header.substring(colon + 1))
        val parentIndex = supertypes.indexOfFirst { it.contains('(') }
        val parent = parentIndex.takeIf { it >= 0 }
            ?.let(supertypes::get)
            ?.let { qualifyDeclaredType(source, it.substringBefore('(')) }
        val interfaces = supertypes
            .filterIndexed { index, _ -> index != parentIndex }
            .map { qualifyDeclaredType(source, it.substringBefore('(')) }
        return ParsedEntityHierarchy(parent, interfaces)
    }

    private fun inheritedEntityEvidence(
        owner: SchemaEntitySnapshot,
        entities: List<SchemaEntitySnapshot>,
    ): InheritedEntityEvidence {
        val attributes = mutableListOf<SchemaInheritedAttributeSnapshot>()
        val traits = mutableListOf<SchemaInheritedTraitSnapshot>()
        val seenEntities = mutableSetOf(owner.qualifiedName)
        val seenAttributes = owner.attributes.mapTo(linkedSetOf(), SchemaEntityAttributeSnapshot::name)
        val seenTraits = owner.traits.toMutableSet()
        var parentReference = owner.extendsClass
        var depth = 1
        while (!parentReference.isNullOrBlank() && depth <= MAX_INHERITANCE_DEPTH) {
            val parent = resolveEntityReference(owner, parentReference, entities) ?: break
            if (!seenEntities.add(parent.qualifiedName)) break
            parent.attributes.forEach { attribute ->
                if (seenAttributes.add(attribute.name)) {
                    attributes += SchemaInheritedAttributeSnapshot(
                        declaredBy = parent.qualifiedName,
                        depth = depth,
                        attribute = attribute,
                    )
                }
            }
            parent.traits.forEach { trait ->
                if (seenTraits.add(trait)) {
                    traits += SchemaInheritedTraitSnapshot(
                        trait = trait,
                        declaredBy = parent.qualifiedName,
                        depth = depth,
                    )
                }
            }
            parentReference = parent.extendsClass
            depth += 1
        }
        return InheritedEntityEvidence(attributes, traits)
    }

    private fun resolveEntityReference(
        owner: SchemaEntitySnapshot,
        rawReference: String,
        entities: List<SchemaEntitySnapshot>,
    ): SchemaEntitySnapshot? {
        val reference = rawReference.substringBefore('<').trim().removeSuffix("?")
        val ownerPackage = owner.qualifiedName.substringBeforeLast('.', "")
        val qualified = if ('.' in reference) reference else "$ownerPackage.$reference"
        return entities.firstOrNull { it.qualifiedName == qualified }
            ?: entities.filter {
                it.className == reference.substringAfterLast('.') &&
                    it.moduleId == owner.moduleId
            }.singleOrNull()
            ?: entities.filter { it.className == reference.substringAfterLast('.') }.singleOrNull()
    }

    private fun qualifyDeclaredType(source: String, rawType: String): String {
        val simple = rawType.trim()
            .substringBefore('<')
            .removeSuffix("?")
            .trim()
        if (simple.isBlank() || '.' in simple) return simple
        val imported = IMPORT_DECLARATION.findAll(source)
            .map { it.groupValues[1] }
            .firstOrNull { it.substringAfterLast('.') == simple }
        if (imported != null) return imported
        val packageName = PACKAGE_DECLARATION.find(source)?.groupValues?.get(1)
        return packageName?.let { "$it.$simple" } ?: simple
    }

    private fun splitTopLevel(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var parentheses = 0
        var angles = 0
        value.forEachIndexed { index, character ->
            when (character) {
                '(' -> parentheses += 1
                ')' -> parentheses = (parentheses - 1).coerceAtLeast(0)
                '<' -> angles += 1
                '>' -> angles = (angles - 1).coerceAtLeast(0)
                ',' -> if (parentheses == 0 && angles == 0) {
                    value.substring(start, index).trim().takeIf(String::isNotBlank)?.let(result::add)
                    start = index + 1
                }
            }
        }
        value.substring(start).trim().takeIf(String::isNotBlank)?.let(result::add)
        return result
    }

    private fun topLevelColon(value: String): Int {
        var parentheses = 0
        var angles = 0
        value.forEachIndexed { index, character ->
            when (character) {
                '(' -> parentheses += 1
                ')' -> parentheses = (parentheses - 1).coerceAtLeast(0)
                '<' -> angles += 1
                '>' -> angles = (angles - 1).coerceAtLeast(0)
                ':' -> if (parentheses == 0 && angles == 0) return index
            }
        }
        return -1
    }

    private fun read(relativePath: String): String? {
        val file = ProjectFileResolver.getInstance(project).resolveFile(relativePath)?.file ?: return null
        if (file.isDirectory) return null
        return runCatching { ProjectSourceText.read(file) }.getOrNull()
    }

    private fun classpathPath(relativePath: String): String {
        val root = resourceRoot(relativePath).trimEnd('/')
        return relativePath.removePrefix("$root/")
            .takeIf { it != relativePath }
            ?: relativePath.substringAfter(
                "/src/main/resources/",
                relativePath.removePrefix("src/main/resources/"),
            )
    }

    private fun resourceRoot(relativePath: String): String {
        val indexedRoot = runCatching {
            val graph = ApplicationGraphService.getInstance(project).graph()
            ProjectSourceDestinationService.getInstance(project)
                .productionResources(graph)
                .asSequence()
                .map(ProjectSourceDestination::sourceRoot)
                .filter { root ->
                    relativePath == root || relativePath.startsWith("${root.trimEnd('/')}/")
                }
                .maxByOrNull(String::length)
        }.getOrNull()
        if (indexedRoot != null) return indexedRoot
        val marker = "/src/main/resources/"
        return when {
            marker in relativePath -> relativePath.substringBefore(marker) + "/src/main/resources"
            relativePath.startsWith("src/main/resources/") -> "src/main/resources"
            else -> relativePath.substringBeforeLast('/')
        }
    }

    private fun String.normalizePath(): String {
        val result = mutableListOf<String>()
        split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                else -> result += part
            }
        }
        return result.joinToString("/")
    }

    private fun finding(
        severity: SchemaFindingSeverity,
        code: String,
        message: String,
        moduleId: String,
        sourceLocator: SourceLocator?,
        entityArtifactId: String? = null,
    ) = SchemaFinding(severity, code, message, moduleId, sourceLocator, entityArtifactId)

    companion object {
        private const val ORACLE_IDENTIFIER_LIMIT = 30
        private const val MAX_INHERITANCE_DEPTH = 32
        private val SAFE_FILE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
        private val PACKAGE_DECLARATION = Regex("""(?m)^\s*package\s+([\w.]+)\s*;?""")
        private val IMPORT_DECLARATION = Regex(
            """(?m)^\s*import\s+(?:static\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;?""",
        )
        private val ENTITY_LISTENERS = Regex(
            """(?s)@\s*(?:[\w.]+\.)?EntityListeners\s*\((.*?)\)""",
        )
        private val TABLE_ANNOTATION = Regex(
            """(?s)@(?:[\w.]+\.)?Table\s*\([^)]*?\bname\s*=\s*"([^"]+)"""",
        )
        private val TABLE_SCHEMA_ANNOTATION = Regex(
            """(?s)@(?:[\w.]+\.)?Table\s*\([^)]*?\bschema\s*=\s*"([^"]+)"""",
        )
        private val TABLE_CATALOG_ANNOTATION = Regex(
            """(?s)@(?:[\w.]+\.)?Table\s*\([^)]*?\bcatalog\s*=\s*"([^"]+)"""",
        )
        private val MAPPED_SUPERCLASS_ANNOTATION =
            Regex("""@\s*(?:[\w.]+\.)?MappedSuperclass\b""")
        private val EMBEDDABLE_ANNOTATION =
            Regex("""@\s*(?:[\w.]+\.)?Embeddable\b""")
        private val JPA_ENTITY_ANNOTATION =
            Regex("""@\s*(?:jakarta\.persistence\.)?Entity\b""")
        private val ANNOTATION_NAME = Regex("""@\s*([\w.]+)""")
        private val STRING_LITERAL = Regex(""""((?:\\.|[^"\\])*)"""")
        private val VALIDATION_GROUPS =
            Regex("""(?s)\bgroups\s*=\s*(?:\{|\[)(.*?)(?:}|\])""")
        private val CLASS_LITERAL = Regex("""([\w.]+)\s*(?:::class|\.class)""")
        private val MANAGED_ATTRIBUTE_ANNOTATIONS = setOf(
            "Column",
            "JoinColumn",
            "JoinColumns",
            "JoinTable",
            "ManyToOne",
            "OneToMany",
            "ManyToMany",
            "OneToOne",
            "Transient",
            "JmixProperty",
            "DependsOnProperties",
            "PropertyDatatype",
            "SystemLevel",
            "Comment",
            "Lob",
            "Composition",
            "OnDelete",
            "OnDeleteInverse",
            "InstanceName",
            "Id",
            "EmbeddedId",
            "JmixId",
            "JmixGeneratedValue",
            "GeneratedValue",
            "SequenceGenerator",
            "Enumerated",
            "JvmField",
        )
        private val ENTITY_ANNOTATION = Regex("""(?s)@Entity\s*\([^)]*?\bname\s*=\s*"([^"]+)"""")
        private val COLUMN_ANNOTATION = Regex("""@Column\s*\([^)]*?\bname\s*=\s*"([^"]+)"""")
        private val JAVA_FIELD_DECLARATION = Regex(
            """(?m)^[\t ]*(?:private|protected|public)[\t ]+([\w.$<>,?\[\]\t ]+?)[\t ]+""" +
                """([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:=\s*[^;\r\n]+)?\s*;""",
        )
        private val KOTLIN_PROPERTY_DECLARATION = Regex(
            """(?m)^[\t ]*(?:(?:private|protected|public|internal)[\t ]+)?""" +
                """(?:lateinit[\t ]+)?(?:val|var)[\t ]+([A-Za-z_$][A-Za-z0-9_$]*)""" +
                """[\t ]*:[\t ]*([^=\n]+?)[\t ]*(?:=[^\n]*)?$""",
        )
        private val RELATION_ANNOTATION = Regex(
            """@\s*(?:[\w.]+\.)?(ManyToOne|OneToMany|ManyToMany|OneToOne)\b""",
        )
        private val TRANSIENT_ANNOTATION = Regex("""@\s*(?:[\w.]+\.)?Transient\b""")
        private val JMIX_PROPERTY_ANNOTATION = Regex("""@\s*(?:[\w.]+\.)?JmixProperty\b""")
        private val COMPOSITION_ANNOTATION = Regex("""@\s*(?:[\w.]+\.)?Composition\b""")
        private val DEPENDS_ON_PROPERTIES = Regex(
            """(?s)@\s*(?:[\w.]+\.)?DependsOnProperties\s*\(\s*(?:value\s*=\s*)?(?:\{\s*)?"([^"]+)"""",
        )
        private val CASCADE_VALUE = Regex("""CascadeType\.([A-Za-z_]+)""")
        private val FETCH_EAGER = Regex("""FetchType\.EAGER\b""")
        private val FETCH_LAZY = Regex("""FetchType\.LAZY\b""")
        private val BOOLEAN_TRUE_ARGUMENT = Regex("""\b(orphanRemoval)\s*=\s*(true)\b""")
        private val ON_DELETE_POLICY = Regex(
            """(?s)@\s*(?:[\w.]+\.)?OnDelete(?:Inverse)?\s*\([^)]*?DeletePolicy\.([A-Za-z_]+)""",
        )
        private val JOIN_TABLE_NAME = Regex(
            """(?s)@\s*(?:[\w.]+\.)?JoinTable\s*\(.*?\bname\s*=\s*"([^"]+)"""",
        )
        private val JOIN_TABLE_OWNER_COLUMN = Regex(
            """(?s)\bjoinColumns\s*=\s*(?:\{\s*)?@\s*(?:[\w.]+\.)?JoinColumn\s*\([^)]*?\bname\s*=\s*"([^"]+)"""",
        )
        private val JOIN_TABLE_INVERSE_COLUMN = Regex(
            """(?s)\binverseJoinColumns\s*=\s*(?:\{\s*)?@\s*(?:[\w.]+\.)?JoinColumn\s*\([^)]*?\bname\s*=\s*"([^"]+)"""",
        )
        private val ID_ANNOTATION = Regex("""@\s*(?:[\w.]+\.)?(?:Id|EmbeddedId)\b""")
        private val STORE_ANNOTATION = Regex("""@Store\s*\(\s*name\s*=\s*"([^"]+)"""")
        private val DB_VIEW = Regex("""@\s*(?:[\w.]+\.)?DbView\b""")
        private val DDL_GENERATION = Regex("""(?s)@DdlGeneration(?:\s*\(([^)]*)\))?""")
        private val NULLABLE_FALSE = Regex("""\bnullable\s*=\s*false\b""")
        private val UNIQUE_TRUE = Regex("""\bunique\s*=\s*true\b""")
        private val CHANGESET_TAG = Regex("""(?is)<changeSet\b([^>]*)>""")
        private val LIQUIBASE_SCHEMA_OPERATION = Regex(
            """(?is)<(createTable|addColumn|createIndex)\b([^>]*)>(.*?)</\1\s*>|""" +
                """<(dropTable|renameTable|dropColumn|renameColumn|modifyDataType|""" +
                """addNotNullConstraint|dropNotNullConstraint|addUniqueConstraint|""" +
                """dropUniqueConstraint|dropIndex|addForeignKeyConstraint|""" +
                """dropForeignKeyConstraint)\b([^>]*)/?>""",
        )
        private val LIQUIBASE_COLUMN = Regex(
            """(?is)<column\b([^>]*?)(?:/\s*>|>(.*?)</column\s*>)""",
        )
        private val LIQUIBASE_CONSTRAINTS = Regex("""(?is)<constraints\b([^>]*)/?>""")
        private val TABLE_REFERENCE = Regex("""(?is)\b(?:tableName|baseTableName|referencedTableName)\s*=\s*"([^"]+)"""")
        private val INCLUDE_TAG = Regex("""(?is)<include(?!All)\b([^>]*)/?>""")
        private val INCLUDE_ALL_TAG = Regex("""(?is)<includeAll\b([^>]*)/?>""")
        private val XML_ATTRIBUTE = Regex("""([A-Za-z_][\w:.-]*)\s*=\s*"([^"]*)"""")
        private val ROOT_CLOSE = Regex("""(?i)</databaseChangeLog\s*>""")
        private val RAW_SQL = Regex("""(?is)<sql(?:\s|>)""")
        private val RAW_SQL_WITHOUT_DBMS = Regex("""(?is)<sql\b(?![^>]*\bdbms\s*=)[^>]*>""")
        private val RAW_SQL_BLOCK = Regex("""(?is)<sql\b[^>]*>(.*?)</sql\s*>""")
        private val ROLLBACK_BLOCK = Regex("""(?is)<rollback\b[^>]*>.*?</rollback\s*>""")
        private val RAW_SQL_SCHEMA_OPERATION =
            Regex("""(?i)\b(?:ALTER|CREATE|DROP|TRUNCATE|RENAME|GRANT|REVOKE)\b""")
        private const val DATA_ONLY_BACKFILL_MARKER = "/* JVW_DATA_ONLY_BACKFILL */"
        private val BUSINESS_IDENTIFIER = Regex("""(?i)(number|code|applicationNo|applicationNumber|loanNo|loanNumber)$""")
        private val MONEY_NAME = Regex("""(?i)(amount|balance|salary|wage|rate|price|total|interest|principal|deduction)""")
        private val SCALAR_TYPES = setOf(
            "String", "Character", "Char", "Integer", "Int", "Long", "Double", "Float",
            "BigDecimal", "Boolean", "Date",
            "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "UUID", "byte[]",
            "ByteArray", "URI", "FileRef", "int", "long", "double", "float", "boolean",
        )

        fun getInstance(project: Project): SchemaWorkspaceService =
            project.getService(SchemaWorkspaceService::class.java)
    }
}

private data class ParsedSourceField(
    val type: String,
    val name: String,
    val declaration: String,
)

private data class ParsedEntityHierarchy(
    val extendsClass: String? = null,
    val interfaces: List<String> = emptyList(),
)

private data class InheritedEntityEvidence(
    val attributes: List<SchemaInheritedAttributeSnapshot>,
    val traits: List<SchemaInheritedTraitSnapshot>,
)

private data class ParsedAttributeMetadata(
    val comment: String?,
    val systemLevel: Boolean,
    val lob: Boolean,
    val jmixProperty: Boolean,
    val dependsOnProperties: List<String>,
    val propertyDatatype: String?,
    val validations: List<ValidationModel>,
    val readOnly: Boolean,
    val unmanagedAnnotations: List<String>,
    val sqlType: String?,
)

private data class ExpectedSchemaColumn(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val unique: Boolean,
    val primaryKey: Boolean = false,
    val foreignKey: SchemaPhysicalForeignKeySnapshot? = null,
)

private data class MutablePhysicalTable(
    var name: String,
    val columns: MutableMap<String, SchemaPhysicalColumnSnapshot> = linkedMapOf(),
    val foreignKeys: MutableMap<String, SchemaPhysicalForeignKeySnapshot> = linkedMapOf(),
    val uniqueConstraints: MutableMap<String, List<String>> = linkedMapOf(),
    val indexes: MutableMap<String, SchemaPhysicalIndexSnapshot> = linkedMapOf(),
    val sourcePaths: MutableSet<String> = linkedSetOf(),
) {
    fun snapshot(): SchemaPhysicalTableSnapshot = SchemaPhysicalTableSnapshot(
        name = name,
        columns = columns.values.sortedBy(SchemaPhysicalColumnSnapshot::name),
        foreignKeys = foreignKeys.values.sortedBy(SchemaPhysicalForeignKeySnapshot::constraintName),
        uniqueConstraints = uniqueConstraints.entries
            .sortedBy(Map.Entry<String, List<String>>::key)
            .map { (constraintName, columns) ->
                SchemaPhysicalUniqueConstraintSnapshot(constraintName, columns)
            },
        indexes = indexes.values.sortedBy(SchemaPhysicalIndexSnapshot::name),
        sourcePaths = sourcePaths.toList(),
    )
}

data class SchemaWorkspaceResponse(
    val accepted: Boolean,
    val snapshotDigest: String,
    val modules: List<SchemaModuleSnapshot>,
    val stores: List<SchemaDataStoreSnapshot>,
    val entities: List<SchemaEntitySnapshot>,
    val repositories: List<SchemaRepositorySnapshot>,
    val changelogs: List<SchemaChangelogSnapshot>,
    val physicalSchemas: List<SchemaPhysicalStoreSnapshot>,
    val drifts: List<SchemaDriftSnapshot>,
    val findings: List<SchemaFinding>,
    val issues: List<WorkspaceChangeIssue>,
)

data class SchemaRepositorySnapshot(
    val artifactId: String,
    val moduleId: String,
    val interfaceName: String,
    val qualifiedName: String,
    val entityQualifiedName: String,
    val idType: String,
    val sourceLanguage: EntitySourceLanguage,
    val sourceLocator: SourceLocator,
    val config: DataRepositoryConfig,
    val methodEvidence: List<SchemaRepositoryMethodEvidence>,
)

data class SchemaRepositoryMethodEvidence(
    val sourceSignature: String,
    val editable: Boolean,
    val issue: String? = null,
)

data class SchemaModuleSnapshot(
    val moduleId: String,
    val projectId: String?,
    val entityCount: Int,
    val changelogCount: Int,
    val storeCount: Int,
    val findingCount: Int,
)

data class SchemaDataStoreSnapshot(
    val id: String,
    val name: String,
    val moduleId: String,
    val configuredPath: String,
    val configurationLocator: SourceLocator?,
    val rootChangelogPath: String?,
    val rootLocator: SourceLocator?,
    val includeMode: SchemaIncludeMode,
    val includeTargets: List<SchemaIncludeTarget>,
    val generatedDirectory: String?,
)

data class SchemaIncludeTarget(
    val path: String,
    val includeAll: Boolean,
    val relativeToChangelogFile: Boolean,
)

enum class SchemaIncludeMode {
    INCLUDE_ALL,
    EXPLICIT,
    DIRECT,
    MISSING,
}

data class SchemaEntitySnapshot(
    val artifactId: String,
    val moduleId: String,
    val className: String,
    val qualifiedName: String,
    val entityType: EntityType = EntityType.ENTITY,
    val entityName: String,
    val tableName: String,
    val storeName: String,
    val idType: IdType,
    val idColumnName: String,
    val databaseView: Boolean,
    val ddlMode: SchemaDdlMode,
    val protectedUnmappedColumns: List<String> = emptyList(),
    val sourceLocator: SourceLocator,
    val attributes: List<SchemaEntityAttributeSnapshot>,
    val migrationCoverage: SchemaMigrationCoverage,
    val migrationArtifactIds: List<String>,
    val tableSchema: String? = null,
    val tableCatalog: String? = null,
    val traits: List<TraitType> = emptyList(),
    val extendsClass: String? = null,
    val implementsInterfaces: List<String> = emptyList(),
    val lifecycleCallbacks: List<LifecycleCallback> = emptyList(),
    val entityListeners: List<String> = emptyList(),
    val inheritance: InheritanceConfig? = null,
    val inheritedAttributes: List<SchemaInheritedAttributeSnapshot> = emptyList(),
    val inheritedTraits: List<SchemaInheritedTraitSnapshot> = emptyList(),
)

data class SchemaInheritedAttributeSnapshot(
    val declaredBy: String,
    val depth: Int,
    val attribute: SchemaEntityAttributeSnapshot,
)

data class SchemaInheritedTraitSnapshot(
    val trait: TraitType,
    val declaredBy: String,
    val depth: Int,
)

data class SchemaEntityAttributeSnapshot(
    val artifactId: String,
    val name: String,
    val javaType: String,
    val columnName: String,
    val nullable: Boolean,
    val unique: Boolean,
    val length: Int? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    val sqlType: String? = null,
    val persistent: Boolean = true,
    val association: Boolean,
    val associationDetails: SchemaAssociationSnapshot? = null,
    val embedded: Boolean = false,
    val embeddedClass: String? = null,
    val embeddedAttributeOverrides: List<EmbeddedAttributeOverride> = emptyList(),
    val embeddedAssociationOverrides: List<EmbeddedAssociationOverride> = emptyList(),
    val moneyCandidate: Boolean,
    val comment: String? = null,
    val systemLevel: Boolean = false,
    val lob: Boolean = false,
    val jmixProperty: Boolean = false,
    val dependsOnProperties: List<String> = emptyList(),
    val propertyDatatype: String? = null,
    val validations: List<ValidationModel> = emptyList(),
    val readOnly: Boolean = false,
    val unmanagedAnnotations: List<String> = emptyList(),
)

data class SchemaAssociationSnapshot(
    val associationType: AssociationType,
    val relatedEntity: String,
    val relatedTableName: String? = null,
    val relatedIdColumnName: String = "ID",
    val relatedIdType: IdType = IdType.UUID,
    val localIdAttributeName: String? = null,
    val mappedBy: String? = null,
    val joinColumnName: String? = null,
    val joinTable: JoinTableConfig? = null,
    val cascade: List<CascadeType> = emptyList(),
    val fetch: FetchType = FetchType.LAZY,
    val collectionType: AssociationCollectionType = AssociationCollectionType.LIST,
    val crossDataStore: Boolean = false,
    val orphanRemoval: Boolean = false,
    val composition: Boolean = false,
    val onDelete: String? = null,
)

enum class SchemaDdlMode {
    CREATE_AND_DROP,
    CREATE_ONLY,
    DISABLED,
}

enum class SchemaMigrationCoverage {
    COVERED,
    MISSING,
    DISABLED,
}

data class SchemaChangelogSnapshot(
    val artifactId: String,
    val moduleId: String,
    val relativePath: String,
    val sourceLocator: SourceLocator,
    val root: Boolean,
    val changeSetCount: Int,
    val includes: List<SchemaIncludeTarget>,
    val tables: List<String>,
    val containsRawSql: Boolean,
)

data class SchemaPhysicalStoreSnapshot(
    val storeId: String,
    val moduleId: String,
    val complete: Boolean,
    val changelogPaths: List<String>,
    val tables: List<SchemaPhysicalTableSnapshot>,
)

data class SchemaPhysicalTableSnapshot(
    val name: String,
    val columns: List<SchemaPhysicalColumnSnapshot>,
    val foreignKeys: List<SchemaPhysicalForeignKeySnapshot>,
    val uniqueConstraints: List<SchemaPhysicalUniqueConstraintSnapshot> = emptyList(),
    val indexes: List<SchemaPhysicalIndexSnapshot> = emptyList(),
    val sourcePaths: List<String>,
)

data class SchemaPhysicalColumnSnapshot(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val unique: Boolean,
    val primaryKey: Boolean,
)

data class SchemaPhysicalForeignKeySnapshot(
    val constraintName: String,
    val baseColumnNames: String,
    val referencedTableName: String,
    val referencedColumnNames: String,
    val onDelete: String? = null,
)

data class SchemaPhysicalIndexSnapshot(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

data class SchemaPhysicalUniqueConstraintSnapshot(
    val name: String,
    val columns: List<String>,
)

data class SchemaDriftSnapshot(
    val id: String,
    val kind: SchemaDriftKind,
    val severity: SchemaDriftSeverity,
    val safety: SchemaDriftSafety,
    val confidence: SchemaDriftConfidence,
    val moduleId: String,
    val storeId: String,
    val entityArtifactId: String,
    val entitySourceLocator: SourceLocator,
    val tableName: String,
    val columnName: String? = null,
    val message: String,
    val suggestion: SchemaDriftSuggestion? = null,
)

enum class SchemaDriftKind {
    TABLE_MISSING,
    COLUMN_MISSING,
    TYPE_MISMATCH,
    NULLABILITY_MISMATCH,
    UNIQUE_CONSTRAINT_MISSING,
    FOREIGN_KEY_MISSING,
    UNMAPPED_COLUMN,
}

enum class SchemaDriftSeverity {
    INFO,
    WARNING,
    ERROR,
}

enum class SchemaDriftSafety {
    SAFE,
    DATA_CHECK_REQUIRED,
    REVIEW,
}

enum class SchemaDriftConfidence {
    HIGH,
    PARTIAL,
}

data class SchemaDriftSuggestion(
    val changeType: String,
    val tableName: String,
    val columnName: String? = null,
    val columnType: String? = null,
    val nullable: Boolean? = null,
    val columns: List<SchemaSuggestedColumn> = emptyList(),
    val newDataType: String? = null,
    val newColumnName: String? = null,
    val constraintName: String? = null,
    val columnNames: List<String> = emptyList(),
    val baseTableName: String? = null,
    val baseColumnNames: String? = null,
    val referencedTableName: String? = null,
    val referencedColumnNames: String? = null,
    val onDelete: String? = null,
)

data class SchemaSuggestedColumn(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val unique: Boolean = false,
    val primaryKey: Boolean = false,
)

data class SchemaFinding(
    val severity: SchemaFindingSeverity,
    val code: String,
    val message: String,
    val moduleId: String,
    val sourceLocator: SourceLocator?,
    val entityArtifactId: String?,
)

enum class SchemaFindingSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class SchemaMigrationChangeRequest(
    val storeId: String,
    val migration: MigrationModel,
    val fileName: String? = null,
)

data class SchemaMigrationApplyRequest(
    val change: SchemaMigrationChangeRequest,
    val expectedPlanDigest: String,
)

internal data class SchemaMigrationProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    fun rejectedPreview(): WorkspaceChangePreviewResponse = WorkspaceChangePreviewResponse(
        accepted = false,
        changeSetId = "schema-migration:rejected",
        label = "Schema migration rejected",
        planDigest = null,
        files = emptyList(),
        issues = issues,
    )

    companion object {
        fun failure(code: String, message: String, path: String? = null) =
            SchemaMigrationProposal(
                changeSet = null,
                issues = listOf(WorkspaceChangeIssue(code, message, path)),
            )
    }
}

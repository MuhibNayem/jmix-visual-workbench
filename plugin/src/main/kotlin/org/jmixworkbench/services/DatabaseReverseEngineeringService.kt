package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.model.AttributeType
import java.net.URL
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Driver
import java.sql.ResultSet
import java.sql.Types
import java.util.Locale
import java.util.Properties

/**
 * Live, read-only JDBC schema inspection for partial entity reverse engineering.
 *
 * Connection secrets are resolved only inside the backend and never cross the
 * JCEF bridge. JDBC drivers are loaded from the synced IntelliJ project model,
 * so the plugin reuses the application's locked driver instead of bundling a
 * second version that can disagree with production.
 */
@Service(Service.Level.PROJECT)
class DatabaseReverseEngineeringService(
    private val project: Project,
) : Disposable {
    private val driverLoaderLock = Any()
    private var cachedDriverUrls: List<URL> = emptyList()
    private var cachedDriverLoader: URLClassLoader? = null
    private val cachedDriverClassNames = linkedSetOf<String>()

    fun inspectEntityTable(
        request: DatabaseEntityTableInspectionRequest,
    ): DatabaseEntityTableInspectionResponse {
        val tableName = request.tableName.trim()
        if (
            tableName.isBlank() ||
            tableName.length > MAX_IDENTIFIER_LENGTH ||
            tableName.any { it.isISOControl() } ||
            request.catalogName?.let(::invalidMetadataName) == true ||
            request.schemaName?.let(::invalidMetadataName) == true ||
            request.expectedEntityQualifiedName?.let {
                it.length > MAX_QUALIFIED_NAME_LENGTH ||
                    !QUALIFIED_JVM_NAME.matches(it)
            } == true
        ) {
            return DatabaseEntityTableInspectionResponse.failure(
                "JVW-DB-TABLE-NAME-INVALID",
                "Enter a valid database table or view name.",
            )
        }
        val result = withReadOnlyConnection(
            storeId = request.storeId,
            connectTimeoutSeconds = request.connectTimeoutSeconds,
            networkTimeoutSeconds = request.networkTimeoutSeconds,
        ) { connection, context ->
            inspectConnected(
                connection,
                context.store,
                request,
                context.workspace,
                context.configuration,
            )
        }
        return result.value ?: DatabaseEntityTableInspectionResponse.failure(
            result.issue?.code ?: "JVW-DB-INSPECTION-FAILED",
            result.issue?.message ?: "Database inspection failed.",
        )
    }

    fun browseEntityTables(
        request: DatabaseEntityTableBrowseRequest,
    ): DatabaseEntityTableBrowseResponse {
        if (
            request.catalogName?.let(::invalidMetadataName) == true ||
            request.schemaName?.let(::invalidMetadataName) == true ||
            request.search.length > MAX_SEARCH_LENGTH
        ) {
            return DatabaseEntityTableBrowseResponse.failure(
                "JVW-DB-BROWSE-FILTER-INVALID",
                "Catalog, schema, and search filters must be concise database metadata names.",
            )
        }
        val result = withReadOnlyConnection(
            storeId = request.storeId,
            connectTimeoutSeconds = request.connectTimeoutSeconds,
            networkTimeoutSeconds = request.networkTimeoutSeconds,
        ) { connection, context ->
            browseConnected(connection, context.configuration, request)
        }
        return result.value ?: DatabaseEntityTableBrowseResponse.failure(
            result.issue?.code ?: "JVW-DB-BROWSE-FAILED",
            result.issue?.message ?: "Database schema browsing failed.",
        )
    }

    fun planEntityImport(
        request: DatabaseEntityImportRequest,
    ): DatabaseEntityImportPlanResponse {
        if (
            request.moduleId.isBlank() ||
            !QUALIFIED_JVM_NAME.matches(request.packageName) ||
            request.selectedTables.isEmpty() ||
            request.selectedTables.size > MAX_IMPORT_SELECTION ||
            request.selectedTables.any {
                invalidMetadataName(it.name) ||
                    it.catalog?.let(::invalidMetadataName) == true ||
                    it.schema?.let(::invalidMetadataName) == true
            } ||
            request.identifierOverrides.values.any { columns ->
                columns.isEmpty() || columns.size > MAX_COMPOSITE_IDENTIFIER_COLUMNS ||
                    columns.any(::invalidMetadataName)
            } ||
            request.identifierOverrides.size > MAX_IMPORT_TABLES ||
            request.identifierOverrides.keys.any(::invalidQualifiedMetadataName) ||
            request.classNameOverrides.size > MAX_IMPORT_TABLES ||
            request.classNameOverrides.keys.any(::invalidQualifiedMetadataName) ||
            request.classNameOverrides.values.any {
                it.length > MAX_IDENTIFIER_LENGTH || !JVM_IDENTIFIER.matches(it)
            } ||
            (request.profileId == null) != (request.profileLabel == null) ||
            request.profileId?.let { !DATABASE_IMPORT_PROFILE_ID.matches(it) } == true ||
            request.profileLabel?.let {
                it.isBlank() || it.length > MAX_PROFILE_LABEL_LENGTH || it.any(Char::isISOControl)
            } == true
        ) {
            return DatabaseEntityImportPlanResponse.failure(
                "JVW-DB-IMPORT-REQUEST-INVALID",
                "Select valid tables, one indexed module/data store, and a valid target package.",
            )
        }
        val result = withReadOnlyConnection(
            storeId = request.storeId,
            connectTimeoutSeconds = request.connectTimeoutSeconds,
            networkTimeoutSeconds = request.networkTimeoutSeconds,
        ) { connection, context ->
            planConnectedEntityImport(connection, context, request)
        }
        return result.value ?: DatabaseEntityImportPlanResponse.failure(
            result.issue?.code ?: "JVW-DB-IMPORT-FAILED",
            result.issue?.message ?: "Database entity import planning failed.",
        )
    }

    fun verifyEntityTypeExpansion(
        descriptor: EntityAttributeTypeExpansionDescriptor,
        connectTimeoutSeconds: Int = 10,
        networkTimeoutSeconds: Int = 30,
    ): DatabaseEntityTypeExpansionVerification {
        val result = withReadOnlyConnection(
            storeId = descriptor.storeId,
            connectTimeoutSeconds = connectTimeoutSeconds,
            networkTimeoutSeconds = networkTimeoutSeconds,
        ) { connection, context ->
            verifyConnectedExpansion(connection, context.configuration, descriptor)
        }
        return result.value ?: DatabaseEntityTypeExpansionVerification.failure(
            result.issue?.code ?: "JVW-ENTITY-TYPE-CUTOVER-DB-FAILED",
            result.issue?.message ?: "Live expansion verification failed.",
        )
    }

    private fun inspectConnected(
        connection: Connection,
        store: SchemaDataStoreSnapshot,
        request: DatabaseEntityTableInspectionRequest,
        workspace: SchemaWorkspaceResponse,
        configuration: DatabaseConnectionConfiguration,
    ): DatabaseEntityTableInspectionResponse {
        val metadata = connection.metaData
        val matchedTables = findTables(
            metadata,
            request.catalogName?.trim()?.takeIf(String::isNotBlank) ?: connection.catalog,
            request.schemaName?.trim()?.takeIf(String::isNotBlank),
            request.tableName.trim(),
        )
        if (matchedTables.isEmpty()) {
            return DatabaseEntityTableInspectionResponse.failure(
                "JVW-DB-TABLE-MISSING",
                "Table or view ${request.tableName} was not found in the connected database.",
            )
        }
        if (matchedTables.size > 1) {
            val schemas = matchedTables
                .map { it.schema ?: "<default>" }
                .distinct()
                .sorted()
                .joinToString()
            return DatabaseEntityTableInspectionResponse.failure(
                "JVW-DB-TABLE-AMBIGUOUS",
                "${request.tableName} exists in multiple schemas ($schemas). Enter the intended schema and inspect again.",
            )
        }
        val table = matchedTables.single()
        val primaryKeys = readPrimaryKeys(metadata, table)
        val foreignKeys = readForeignKeys(metadata, table)
        val indexes = readIndexes(metadata, table)
        val existing = resolveMappedEntity(
            workspace = workspace,
            store = store,
            table = table,
            defaultCatalog = connection.catalog,
            defaultSchema = runCatching { connection.schema }.getOrNull(),
            expectedQualifiedName = request.expectedEntityQualifiedName,
        )
        val mappedColumns = existing?.attributes
            .orEmpty()
            .map { it.columnName.uppercase(Locale.ROOT) }
            .toSet() + setOfNotNull(existing?.idColumnName?.uppercase(Locale.ROOT))
        val entitiesByTable = workspace.entities
            .filter { it.storeName == store.name }
            .associateBy { it.tableName.uppercase(Locale.ROOT) }
        val columns = readColumns(metadata, table).map { column ->
            val foreignKey = foreignKeys.firstOrNull {
                it.columnName.equals(column.name, ignoreCase = true)
            }
            val relatedEntity = foreignKey?.referencedTableName
                ?.uppercase(Locale.ROOT)
                ?.let(entitiesByTable::get)
            val suggestion = DatabaseTypeMapper.suggest(
                column = column,
                primaryKey = column.name.uppercase(Locale.ROOT) in primaryKeys,
                foreignKey = foreignKey,
                relatedEntity = relatedEntity,
            )
            column.copy(
                primaryKey = suggestion.primaryKey,
                alreadyMapped = column.name.uppercase(Locale.ROOT) in mappedColumns,
                suggestion = suggestion,
            )
        }
        val dependencyTables = foreignKeys
            .map(DatabaseForeignKeySnapshot::referencedTableName)
            .filterNot { it.equals(table.name, ignoreCase = true) }
            .distinctBy { it.uppercase(Locale.ROOT) }
            .sorted()
        val warnings = buildList {
            if (primaryKeys.isEmpty() && table.type == "TABLE") {
                add(
                    WorkspaceChangeIssue(
                        "JVW-DB-TABLE-WITHOUT-PK",
                        "${table.name} has no primary key. Choose an identifier before generating a new entity.",
                    ),
                )
            }
            columns.filter { it.suggestion.unsupportedReason != null }.forEach { column ->
                add(
                    WorkspaceChangeIssue(
                        "JVW-DB-COLUMN-TYPE-UNSUPPORTED",
                        "${table.name}.${column.name}: ${column.suggestion.unsupportedReason}",
                    ),
                )
            }
        }
        val digest = CanonicalDiscoveryJson.sha256(
            buildString {
                append(store.id).append('\u0000')
                append(table.catalog.orEmpty()).append('\u0000')
                append(table.schema.orEmpty()).append('\u0000')
                append(table.name).append('\u0000')
                columns.forEach {
                    append(it.name).append(':')
                        .append(it.jdbcType).append(':')
                        .append(it.typeName).append(':')
                        .append(it.size).append(':')
                        .append(it.scale).append(':')
                        .append(it.nullable).append('\u0000')
                }
                foreignKeys.forEach {
                    append(it.name.orEmpty()).append(':')
                        .append(it.columnName).append(':')
                        .append(it.referencedTableName).append(':')
                        .append(it.referencedColumnName).append('\u0000')
                }
            },
        )
        return DatabaseEntityTableInspectionResponse(
            accepted = true,
            snapshotDigest = digest,
            storeId = store.id,
            database = DatabaseProductSnapshot(
                name = metadata.databaseProductName.orEmpty(),
                version = metadata.databaseProductVersion.orEmpty(),
                driverName = metadata.driverName.orEmpty(),
                driverVersion = metadata.driverVersion.orEmpty(),
                urlFingerprint = CanonicalDiscoveryJson.sha256(configuration.url).take(16),
            ),
            table = table.copy(
                columns = columns,
                primaryKeyColumns = primaryKeys.sorted(),
                foreignKeys = foreignKeys,
                indexes = indexes,
                dependencyTables = dependencyTables,
            ),
            existingEntityQualifiedName = existing?.qualifiedName,
            issues = warnings,
        )
    }

    private fun browseConnected(
        connection: Connection,
        configuration: DatabaseConnectionConfiguration,
        request: DatabaseEntityTableBrowseRequest,
    ): DatabaseEntityTableBrowseResponse {
        val metadata = connection.metaData
        val requestedCatalog = request.catalogName?.trim()?.takeIf(String::isNotBlank)
        val effectiveCatalog = requestedCatalog ?: connection.catalog
        val catalogs = linkedSetOf<String>()
        connection.catalog?.takeIf(String::isNotBlank)?.let(catalogs::add)
        runCatching {
            metadata.catalogs.use { rows ->
                while (rows.next() && catalogs.size < MAX_METADATA_NAMES) {
                    rows.stringOrNull("TABLE_CAT")?.let(catalogs::add)
                }
            }
        }
        val schemas = linkedSetOf<DatabaseSchemaReference>()
        runCatching {
            metadata.schemas.use { rows ->
                while (rows.next() && schemas.size < MAX_METADATA_NAMES) {
                    val name = rows.stringOrNull("TABLE_SCHEM") ?: continue
                    val catalog = rows.stringOrNull("TABLE_CATALOG")
                    if (
                        effectiveCatalog == null ||
                        catalog == null ||
                        catalog.equals(effectiveCatalog, ignoreCase = true)
                    ) {
                        schemas += DatabaseSchemaReference(catalog, name)
                    }
                }
            }
        }
        val schemaPatterns = request.schemaName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let {
                linkedSetOf(
                    it,
                    it.uppercase(Locale.ROOT),
                    it.lowercase(Locale.ROOT),
                ).toList()
            }
            ?: listOf(null)
        val search = request.search.trim()
        val types = if (request.includeViews) {
            arrayOf("TABLE", "VIEW")
        } else {
            arrayOf("TABLE")
        }
        val tables = linkedMapOf<String, DatabaseTableReference>()
        var truncated = false
        schemaPatterns.forEach { schemaPattern ->
            if (truncated) return@forEach
            metadata.getTables(effectiveCatalog, schemaPattern, "%", types).use { rows ->
                while (rows.next()) {
                    val name = rows.stringOrNull("TABLE_NAME") ?: continue
                    val schema = rows.stringOrNull("TABLE_SCHEM")
                    if (
                        request.schemaName?.isNotBlank() == true &&
                        schema?.equals(request.schemaName.trim(), ignoreCase = true) != true
                    ) {
                        continue
                    }
                    if (
                        search.isNotBlank() &&
                        !name.contains(search, ignoreCase = true)
                    ) {
                        continue
                    }
                    val table = DatabaseTableReference(
                        catalog = rows.stringOrNull("TABLE_CAT"),
                        schema = schema,
                        name = name,
                        type = rows.stringOrNull("TABLE_TYPE").orEmpty().ifBlank { "TABLE" },
                        remarks = rows.stringOrNull("REMARKS"),
                    )
                    val key = listOf(
                        table.catalog.orEmpty(),
                        table.schema.orEmpty(),
                        table.name,
                        table.type,
                    ).joinToString("\u0000").uppercase(Locale.ROOT)
                    tables.putIfAbsent(key, table)
                    if (tables.size > request.limit.coerceIn(1, MAX_BROWSE_TABLES)) {
                        truncated = true
                        break
                    }
                }
            }
        }
        val limit = request.limit.coerceIn(1, MAX_BROWSE_TABLES)
        return DatabaseEntityTableBrowseResponse(
            accepted = true,
            storeId = request.storeId,
            database = databaseProduct(metadata, configuration),
            activeCatalog = effectiveCatalog,
            catalogs = catalogs.sortedWith(String.CASE_INSENSITIVE_ORDER),
            schemas = schemas.sortedWith(
                compareBy<DatabaseSchemaReference> { it.catalog.orEmpty().lowercase(Locale.ROOT) }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            ),
            tables = tables.values
                .sortedWith(
                    compareBy<DatabaseTableReference> { it.schema.orEmpty().lowercase(Locale.ROOT) }
                        .thenBy { it.type }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                )
                .take(limit),
            truncated = truncated,
            issues = if (truncated) {
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-DB-BROWSE-TRUNCATED",
                        "More than $limit matching tables/views exist. Narrow the catalog, schema, or search filter.",
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    private fun planConnectedEntityImport(
        connection: Connection,
        context: DatabaseReadOnlyContext,
        request: DatabaseEntityImportRequest,
    ): DatabaseEntityImportPlanResponse {
        val store = context.store
        if (store.moduleId != request.moduleId) {
            return DatabaseEntityImportPlanResponse.failure(
                "JVW-DB-IMPORT-MODULE-STORE-MISMATCH",
                "The selected data store belongs to ${store.moduleId}, not ${request.moduleId}.",
            )
        }
        val metadata = connection.metaData
        val queue = ArrayDeque<DatabaseImportQueueEntry>()
        request.selectedTables.distinctBy {
            listOf(it.catalog.orEmpty(), it.schema.orEmpty(), it.name)
                .joinToString("\u0000").uppercase(Locale.ROOT)
        }.forEach {
            queue += DatabaseImportQueueEntry(
                reference = it,
                selectedByUser = true,
                requiredBy = emptySet(),
            )
        }
        val loaded = linkedMapOf<DatabaseObjectKey, DatabaseImportMutableTable>()
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            val matches = findTables(
                metadata,
                entry.reference.catalog ?: connection.catalog,
                entry.reference.schema,
                entry.reference.name,
            )
            if (matches.isEmpty()) {
                return DatabaseEntityImportPlanResponse.failure(
                    "JVW-DB-IMPORT-TABLE-MISSING",
                    "${entry.reference.qualifiedName()} is no longer visible in the connected database.",
                )
            }
            if (matches.size != 1) {
                return DatabaseEntityImportPlanResponse.failure(
                    "JVW-DB-IMPORT-TABLE-AMBIGUOUS",
                    "${entry.reference.name} resolves to multiple database objects. Select an exact catalog and schema.",
                )
            }
            val raw = matches.single()
            val key = DatabaseObjectKey.of(raw)
            val previous = loaded[key]
            if (previous != null) {
                previous.selectedByUser = previous.selectedByUser || entry.selectedByUser
                previous.requiredBy += entry.requiredBy
                continue
            }
            if (loaded.size >= MAX_IMPORT_TABLES) {
                return DatabaseEntityImportPlanResponse.failure(
                    "JVW-DB-IMPORT-DEPENDENCY-LIMIT",
                    "The selected dependency closure exceeds $MAX_IMPORT_TABLES tables. Split the import into bounded modules.",
                )
            }
            val primaryKeys = readOrderedPrimaryKeys(metadata, raw)
            val foreignKeys = readForeignKeys(metadata, raw)
            val table = raw.copy(
                columns = readColumns(metadata, raw),
                primaryKeyColumns = primaryKeys,
                foreignKeys = foreignKeys,
                indexes = readIndexes(metadata, raw),
                dependencyTables = foreignKeys
                    .map(DatabaseForeignKeySnapshot::referencedTableName)
                    .distinctBy { it.uppercase(Locale.ROOT) }
                    .sorted(),
            )
            val mappedEntities = mappedEntityCandidates(
                workspace = context.workspace,
                store = store,
                table = table,
                defaultCatalog = connection.catalog,
                defaultSchema = runCatching { connection.schema }.getOrNull(),
                expectedQualifiedName = null,
            )
            if (mappedEntities.size > 1) {
                return DatabaseEntityImportPlanResponse.failure(
                    "JVW-DB-IMPORT-ENTITY-MAPPING-AMBIGUOUS",
                    "${key.externalName} is mapped by multiple indexed entities: " +
                        mappedEntities.joinToString { it.qualifiedName } +
                        ". Resolve the mapping ambiguity before database-first generation.",
                )
            }
            val existing = mappedEntities.singleOrNull()
            loaded[key] = DatabaseImportMutableTable(
                table = table,
                selectedByUser = entry.selectedByUser,
                requiredBy = entry.requiredBy.toMutableSet(),
                existingEntity = existing,
            )
            if (!request.includeDependencies || existing != null) continue
            DatabaseEntityImportPlanner.groupForeignKeys(foreignKeys).forEach { foreignKey ->
                val targetReference = DatabaseTableReference(
                    catalog = foreignKey.referencedCatalog ?: table.catalog,
                    schema = foreignKey.referencedSchema ?: table.schema,
                    name = foreignKey.referencedTableName,
                    type = "TABLE",
                    remarks = null,
                )
                queue += DatabaseImportQueueEntry(
                    reference = targetReference,
                    selectedByUser = false,
                    requiredBy = setOf(key.externalName),
                )
            }
        }
        return DatabaseEntityImportPlanner.plan(
            request = request,
            store = store,
            database = databaseProduct(metadata, context.configuration),
            importedTables = loaded.values.map {
                DatabaseImportedTable(
                    table = it.table,
                    selectedByUser = it.selectedByUser,
                    requiredBy = it.requiredBy,
                    existingEntity = it.existingEntity,
                )
            },
        )
    }

    private fun resolveMappedEntity(
        workspace: SchemaWorkspaceResponse,
        store: SchemaDataStoreSnapshot,
        table: DatabaseTableSnapshot,
        defaultCatalog: String?,
        defaultSchema: String?,
        expectedQualifiedName: String?,
    ): SchemaEntitySnapshot? = mappedEntityCandidates(
        workspace,
        store,
        table,
        defaultCatalog,
        defaultSchema,
        expectedQualifiedName,
    ).singleOrNull()

    private fun mappedEntityCandidates(
        workspace: SchemaWorkspaceResponse,
        store: SchemaDataStoreSnapshot,
        table: DatabaseTableSnapshot,
        defaultCatalog: String?,
        defaultSchema: String?,
        expectedQualifiedName: String?,
    ): List<SchemaEntitySnapshot> =
        workspace.entities.asSequence()
            .filter { it.storeName == store.name }
            .filter {
                expectedQualifiedName.isNullOrBlank() ||
                    it.qualifiedName == expectedQualifiedName
            }
            .filter { entity ->
                entity.tableName.equals(table.name, ignoreCase = true) &&
                    databaseQualifierMatches(entity.tableSchema, table.schema, defaultSchema) &&
                    databaseQualifierMatches(entity.tableCatalog, table.catalog, defaultCatalog)
            }
            .toList()

    private fun verifyConnectedExpansion(
        connection: Connection,
        configuration: DatabaseConnectionConfiguration,
        descriptor: EntityAttributeTypeExpansionDescriptor,
    ): DatabaseEntityTypeExpansionVerification {
        val metadata = connection.metaData
        val matchedTables = findTables(
            metadata,
            connection.catalog,
            descriptor.schemaName,
            descriptor.tableName,
        )
        if (matchedTables.isEmpty()) {
            return DatabaseEntityTypeExpansionVerification.failure(
                "JVW-ENTITY-TYPE-CUTOVER-TABLE-MISSING",
                "Deployed table ${descriptor.qualifiedTableName} was not found.",
            )
        }
        if (matchedTables.size > 1) {
            return DatabaseEntityTypeExpansionVerification.failure(
                "JVW-ENTITY-TYPE-CUTOVER-TABLE-AMBIGUOUS",
                "${descriptor.tableName} exists in multiple schemas. Use an explicit schema-qualified entity mapping.",
            )
        }
        val table = matchedTables.single()
        val columns = readColumns(metadata, table)
        val original = columns.singleOrNull {
            it.name.equals(descriptor.originalColumnName, ignoreCase = true)
        } ?: return DatabaseEntityTypeExpansionVerification.failure(
            "JVW-ENTITY-TYPE-CUTOVER-ORIGINAL-MISSING",
            "Original column ${descriptor.originalColumnName} is absent. Cutover cannot prove rollback safety.",
        )
        val shadow = columns.singleOrNull {
            it.name.equals(descriptor.shadowColumnName, ignoreCase = true)
        } ?: return DatabaseEntityTypeExpansionVerification.failure(
            "JVW-ENTITY-TYPE-CUTOVER-SHADOW-MISSING",
            "Shadow column ${descriptor.shadowColumnName} is not deployed yet.",
        )
        if (!DatabaseSqlTypeCompatibility.accepts(descriptor.targetSqlType, shadow)) {
            return DatabaseEntityTypeExpansionVerification.failure(
                "JVW-ENTITY-TYPE-CUTOVER-SHADOW-TYPE-MISMATCH",
                "Shadow column ${shadow.name} is ${shadow.typeName}" +
                    shadow.size?.let { "($it${shadow.scale?.let { scale -> ",$scale" }.orEmpty()})" }.orEmpty() +
                    ", not the required ${descriptor.targetSqlType}.",
            )
        }
        val tableSql = qualifiedSqlIdentifier(metadata, table.schema, table.name)
        val originalSql = quotedSqlIdentifier(metadata, original.name)
        val shadowSql = quotedSqlIdentifier(metadata, shadow.name)
        val inconsistentBackfill = connection.prepareStatement(
            DatabaseBackfillVerificationSql.query(tableSql, originalSql, shadowSql),
        ).use { statement ->
            statement.queryTimeout = 30
            statement.maxRows = 1
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Backfill verification returned no result." }
                rows.getLong(1)
            }
        }
        if (inconsistentBackfill != 0L) {
            return DatabaseEntityTypeExpansionVerification.failure(
                "JVW-ENTITY-TYPE-CUTOVER-BACKFILL-INCONSISTENT",
                "$inconsistentBackfill deployed row(s) have a missing or different ${shadow.name} value compared with ${original.name}.",
                inconsistentBackfill,
            )
        }
        val database = databaseProduct(metadata, configuration)
        val evidenceDigest = CanonicalDiscoveryJson.sha256(
            listOf(
                descriptor.storeId,
                table.catalog.orEmpty(),
                table.schema.orEmpty(),
                table.name,
                original.name,
                original.jdbcType.toString(),
                shadow.name,
                shadow.jdbcType.toString(),
                shadow.typeName,
                shadow.size?.toString().orEmpty(),
                shadow.scale?.toString().orEmpty(),
                inconsistentBackfill.toString(),
                database.urlFingerprint,
            ).joinToString("\u0000"),
        )
        return DatabaseEntityTypeExpansionVerification(
            accepted = true,
            code = null,
            message = "Live database verified: ${shadow.name} has the required type and exactly matches every non-null ${original.name} value.",
            evidenceDigest = evidenceDigest,
            database = database,
            deployedSchemaName = table.schema,
            deployedTableName = table.name,
            originalColumn = original,
            shadowColumn = shadow,
            inconsistentBackfillRows = 0,
        )
    }

    private fun <T> withReadOnlyConnection(
        storeId: String,
        connectTimeoutSeconds: Int,
        networkTimeoutSeconds: Int,
        operation: (Connection, DatabaseReadOnlyContext) -> T,
    ): DatabaseReadOnlyResult<T> {
        val workspace = SchemaWorkspaceService.getInstance(project).load()
        val store = workspace.stores.firstOrNull { it.id == storeId }
            ?: return DatabaseReadOnlyResult.failure(
                "JVW-DB-STORE-MISSING",
                "The selected Jmix data store no longer exists.",
            )
        val configuration = resolveConnectionConfiguration(store)
            ?: return DatabaseReadOnlyResult.failure(
                "JVW-DB-CONNECTION-CONFIG-MISSING",
                "No complete ${store.name}.datasource URL and JDBC driver settings were found in the active project profile.",
            )
        val password = configuration.password
        val properties = connectionProperties(
            configuration.url,
            connectTimeoutSeconds,
            networkTimeoutSeconds,
        ).apply {
            configuration.username?.let { setProperty("user", it) }
            password?.let { setProperty("password", String(it)) }
        }
        return synchronized(driverLoaderLock) {
            val loader = projectDriverLoader()
            try {
                val driver = loadDriver(configuration, loader)
                    ?: return@synchronized DatabaseReadOnlyResult.failure(
                        "JVW-DB-DRIVER-MISSING",
                        "JDBC driver ${configuration.driverClassName} is not available in the synced project libraries.",
                    )
                cachedDriverClassNames += driver.javaClass.name
                val connection = driver.connect(configuration.url, properties)
                    ?: return@synchronized DatabaseReadOnlyResult.failure(
                        "JVW-DB-DRIVER-URL-REJECTED",
                        "The configured JDBC driver does not accept the data store URL.",
                    )
                connection.use { live ->
                    runCatching { live.isReadOnly = true }
                    runCatching { live.autoCommit = false }
                    runCatching {
                        live.setNetworkTimeout(
                            AppExecutorUtil.getAppExecutorService(),
                            networkTimeoutSeconds.coerceIn(1, 120) * 1_000,
                        )
                    }
                    val value = operation(
                        live,
                        DatabaseReadOnlyContext(store, workspace, configuration),
                    )
                    runCatching { live.rollback() }
                    DatabaseReadOnlyResult(value = value, issue = null)
                }
            } catch (error: Throwable) {
                DatabaseReadOnlyResult.failure(
                    "JVW-DB-INSPECTION-FAILED",
                    redactDatabaseError(error),
                )
            } finally {
                properties.clear()
                password?.fill('\u0000')
            }
        }
    }

    private fun projectDriverLoader(): ClassLoader {
        val urls = projectLibraryUrls()
        if (cachedDriverLoader == null || urls != cachedDriverUrls) {
            cachedDriverLoader?.let(::closeDriverLoader)
            cachedDriverClassNames.clear()
            cachedDriverUrls = urls
            cachedDriverLoader = URLClassLoader(urls.toTypedArray(), javaClass.classLoader)
        }
        return cachedDriverLoader ?: javaClass.classLoader
    }

    private fun closeDriverLoader(loader: URLClassLoader) {
        val current = Thread.currentThread()
        val previousLoader = current.contextClassLoader
        try {
            current.contextClassLoader = loader
            if (cachedDriverClassNames.any { it.startsWith("com.mysql.cj.") }) {
                runCatching {
                    val cleanup = Class.forName(
                        "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
                        false,
                        loader,
                    )
                    val shutdown = cleanup.methods.firstOrNull {
                        it.parameterCount == 0 && it.name == "checkedShutdown"
                    } ?: cleanup.methods.firstOrNull {
                        it.parameterCount == 0 && it.name == "shutdown"
                    }
                    shutdown?.invoke(null)
                }
            }
            if (cachedDriverClassNames.any { it.startsWith("oracle.jdbc.") }) {
                closeOracleDriver(loader)
            }
        } finally {
            current.contextClassLoader = previousLoader
        }
        runCatching { loader.close() }
    }

    private fun closeOracleDriver(loader: ClassLoader) {
        val driver = runCatching {
            Class.forName("oracle.jdbc.driver.OracleDriver", false, loader)
        }.getOrNull() ?: return
        val completeCleanup = driver.declaredMethods.firstOrNull {
            it.name == "deregister" && it.parameterCount == 0
        }
        if (completeCleanup?.trySetAccessible() == true) {
            if (runCatching { completeCleanup.invoke(null) }.isSuccess) return
        }

        // Compatibility fallback for driver releases that rename or encapsulate
        // the aggregate deregistration callback.
        listOf(
            "oracle.jdbc.driver.OracleTimeoutThreadPerVM" to "stopWatchdog",
            "oracle.jdbc.driver.BlockSource\$ThreadedCachingBlockSource" to
                "stopBlockReleaserThread",
            "oracle.net.nt.TimeoutInterruptHandler" to "stopTimer",
            "oracle.jdbc.diagnostics.Diagnostic" to "stopClockTimer",
        ).forEach { (className, methodName) ->
            runCatching {
                val type = Class.forName(className, false, loader)
                val method = type.declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                } ?: return@runCatching
                if (method.trySetAccessible()) method.invoke(null)
            }
        }
        runCatching {
            val executor = driver.getMethod("getExecutorService").invoke(null)
                as? java.util.concurrent.ExecutorService
            executor?.shutdownNow()
        }
    }

    override fun dispose() {
        synchronized(driverLoaderLock) {
            cachedDriverLoader?.let(::closeDriverLoader)
            cachedDriverLoader = null
            cachedDriverUrls = emptyList()
            cachedDriverClassNames.clear()
        }
    }

    private fun resolveConnectionConfiguration(
        store: SchemaDataStoreSnapshot,
    ): DatabaseConnectionConfiguration? {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val candidates = graph.artifacts.filter {
            it.kind == ArtifactKind.CONFIGURATION_PROPERTY &&
                (it.owner.moduleId == store.moduleId || it.owner.moduleId.isBlank())
        }
        val definitionsByKey = candidates.groupBy(ArtifactSnapshot::displayName)
        val baselineValues = definitionsByKey.mapValues { (_, definitions) ->
            definitions.maxByOrNull { artifact ->
                configurationPrecedence(artifact, emptyList())
            }?.let(::exactPropertyValue).orEmpty()
        }
        val activeProfiles = resolvePlaceholders(
            baselineValues["spring.profiles.active"].orEmpty(),
            baselineValues,
            mutableSetOf("spring.profiles.active"),
        )
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val values = definitionsByKey
            .mapValues { (_, definitions) ->
                val selected = definitions.maxByOrNull { artifact ->
                    configurationPrecedence(artifact, activeProfiles)
                }
                selected?.let(::exactPropertyValue).orEmpty()
            }
            .toMutableMap()
        fun resolved(key: String): String? =
            resolvePlaceholders(values[key].orEmpty(), values, mutableSetOf(key))
                .takeIf(String::isNotBlank)
        val prefix = "${store.name}.datasource"
        val url = resolved("$prefix.url") ?: return null
        val driverClass = resolved("$prefix.driver-class-name")
            ?: driverClassFor(url)
            ?: return null
        return DatabaseConnectionConfiguration(
            url = url,
            username = resolved("$prefix.username"),
            password = resolved("$prefix.password")?.toCharArray(),
            driverClassName = driverClass,
        )
    }

    private fun exactPropertyValue(artifact: ArtifactSnapshot): String {
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(artifact.sourceLocator.relativePath)
            ?: return artifact.summary.orEmpty()
        val source = runCatching { ProjectSourceText.read(resolved.file) }
            .getOrDefault("")
        if (resolved.file.extension.equals("properties", ignoreCase = true)) {
            val properties = Properties()
            runCatching { properties.load(source.reader()) }
            return properties.getProperty(artifact.displayName) ?: artifact.summary.orEmpty()
        }
        return artifact.summary.orEmpty()
    }

    private fun configurationPrecedence(
        artifact: ArtifactSnapshot,
        activeProfiles: List<String>,
    ): Int {
        val name = artifact.sourceLocator.relativePath.substringAfterLast('/')
        val profile = PROFILE_FILE.find(name)?.groupValues?.get(1)
        val activeIndex = profile?.let(activeProfiles::indexOf) ?: -1
        return when {
            activeIndex >= 0 -> 300 + activeIndex
            profile != null -> 100
            name == "application.properties" || name == "application.yml" || name == "application.yaml" -> 200
            else -> 0
        }
    }

    private fun connectionProperties(
        url: String,
        connectTimeoutSeconds: Int,
        networkTimeoutSeconds: Int,
    ): Properties {
        val connectSeconds = connectTimeoutSeconds.coerceIn(1, 60)
        val networkSeconds = networkTimeoutSeconds.coerceIn(1, 120)
        val connectMillis = connectSeconds * 1_000
        val networkMillis = networkSeconds * 1_000
        return Properties().apply {
            when {
                url.startsWith("jdbc:postgresql:", ignoreCase = true) -> {
                    setProperty("connectTimeout", connectSeconds.toString())
                    setProperty("socketTimeout", networkSeconds.toString())
                }
                url.startsWith("jdbc:mysql:", ignoreCase = true) ||
                    url.startsWith("jdbc:mariadb:", ignoreCase = true) -> {
                    setProperty("connectTimeout", connectMillis.toString())
                    setProperty("socketTimeout", networkMillis.toString())
                }
                url.startsWith("jdbc:sqlserver:", ignoreCase = true) -> {
                    setProperty("loginTimeout", connectSeconds.toString())
                    setProperty("socketTimeout", networkMillis.toString())
                }
                url.startsWith("jdbc:oracle:", ignoreCase = true) -> {
                    setProperty("oracle.net.CONNECT_TIMEOUT", connectMillis.toString())
                    setProperty("oracle.jdbc.ReadTimeout", networkMillis.toString())
                }
                url.startsWith("jdbc:db2:", ignoreCase = true) -> {
                    setProperty("loginTimeout", connectSeconds.toString())
                    setProperty("blockingReadConnectionTimeout", networkSeconds.toString())
                }
            }
        }
    }

    private fun resolvePlaceholders(
        input: String,
        values: Map<String, String>,
        visited: MutableSet<String>,
    ): String {
        var result = input
        repeat(MAX_PLACEHOLDER_DEPTH) {
            val match = PLACEHOLDER.find(result) ?: return result
            val token = match.groupValues[1]
            val name = token.substringBefore(':')
            val fallback = token.substringAfter(':', "")
            val replacement = System.getenv(name)
                ?: if (name !in visited) {
                    visited += name
                    values[name]?.let { resolvePlaceholders(it, values, visited) }
                } else {
                    null
                }
                ?: fallback
            result = result.replaceRange(match.range, replacement)
        }
        return result
    }

    private fun projectLibraryUrls(): List<URL> {
        val roots = ModuleManager.getInstance(project).modules
            .asSequence()
            .flatMap { module ->
                OrderEnumerator.orderEntries(module)
                    .recursively()
                    .librariesOnly()
                    .classes()
                    .roots
                    .asSequence()
            }
            .distinctBy { it.url }
        return roots.mapNotNull { root ->
            val local = JarFileSystem.getInstance().getVirtualFileForJar(root) ?: root
            runCatching { local.toNioPath().toUri().toURL() }.getOrNull()
        }.sortedBy(URL::toExternalForm).toList()
    }

    private fun loadDriver(
        configuration: DatabaseConnectionConfiguration,
        loader: ClassLoader,
    ): Driver? {
        val type = runCatching {
            Class.forName(configuration.driverClassName, true, loader)
        }.getOrElse {
            runCatching { Class.forName(configuration.driverClassName) }.getOrNull()
        } ?: return null
        return runCatching { type.getDeclaredConstructor().newInstance() as Driver }.getOrNull()
    }

    private fun findTables(
        metadata: DatabaseMetaData,
        catalog: String?,
        schema: String?,
        requested: String,
    ): List<DatabaseTableSnapshot> {
        val matches = linkedMapOf<String, DatabaseTableSnapshot>()
        val patterns = linkedSetOf(requested, requested.uppercase(Locale.ROOT), requested.lowercase(Locale.ROOT))
        val schemaPatterns = if (schema == null) {
            listOf<String?>(null)
        } else {
            linkedSetOf(
                schema,
                schema.uppercase(Locale.ROOT),
                schema.lowercase(Locale.ROOT),
            ).toList()
        }
        schemaPatterns.forEach { schemaPattern ->
            patterns.forEach { pattern ->
                metadata.getTables(catalog, schemaPattern, pattern, arrayOf("TABLE", "VIEW")).use { rows ->
                    while (rows.next()) {
                        val returnedSchema = rows.stringOrNull("TABLE_SCHEM")
                        if (
                            rows.string("TABLE_NAME").equals(requested, ignoreCase = true) &&
                            (schema == null || returnedSchema?.equals(schema, ignoreCase = true) == true)
                        ) {
                            val table = DatabaseTableSnapshot(
                                catalog = rows.stringOrNull("TABLE_CAT"),
                                schema = returnedSchema,
                                name = rows.string("TABLE_NAME"),
                                type = rows.string("TABLE_TYPE"),
                                remarks = rows.stringOrNull("REMARKS"),
                            )
                            val key = listOf(
                                table.catalog.orEmpty(),
                                table.schema.orEmpty(),
                                table.name,
                                table.type,
                            ).joinToString("\u0000").uppercase(Locale.ROOT)
                            matches.putIfAbsent(key, table)
                        }
                    }
                }
            }
        }
        return matches.values.toList()
    }

    private fun readColumns(
        metadata: DatabaseMetaData,
        table: DatabaseTableSnapshot,
    ): List<DatabaseColumnSnapshot> {
        val result = mutableListOf<DatabaseColumnSnapshot>()
        metadata.getColumns(table.catalog, table.schema, table.name, null).use { rows ->
            while (rows.next()) {
                result += DatabaseColumnSnapshot(
                    name = rows.string("COLUMN_NAME"),
                    jdbcType = rows.getInt("DATA_TYPE"),
                    typeName = rows.string("TYPE_NAME"),
                    size = rows.getInt("COLUMN_SIZE").takeIf { !rows.wasNull() },
                    scale = rows.getInt("DECIMAL_DIGITS").takeIf { !rows.wasNull() },
                    nullable = rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    defaultValue = rows.stringOrNull("COLUMN_DEF"),
                    remarks = rows.stringOrNull("REMARKS"),
                    autoIncrement = rows.stringOrNull("IS_AUTOINCREMENT")
                        ?.equals("YES", ignoreCase = true) == true,
                    generated = rows.stringOrNull("IS_GENERATEDCOLUMN")
                        ?.equals("YES", ignoreCase = true) == true,
                    ordinal = rows.getInt("ORDINAL_POSITION"),
                )
            }
        }
        return result.sortedBy(DatabaseColumnSnapshot::ordinal)
    }

    private fun readPrimaryKeys(
        metadata: DatabaseMetaData,
        table: DatabaseTableSnapshot,
    ): Set<String> {
        val keys = linkedSetOf<String>()
        metadata.getPrimaryKeys(table.catalog, table.schema, table.name).use { rows ->
            while (rows.next()) keys += rows.string("COLUMN_NAME").uppercase(Locale.ROOT)
        }
        return keys
    }

    private fun readOrderedPrimaryKeys(
        metadata: DatabaseMetaData,
        table: DatabaseTableSnapshot,
    ): List<String> {
        val keys = mutableListOf<Pair<Int, String>>()
        metadata.getPrimaryKeys(table.catalog, table.schema, table.name).use { rows ->
            var fallbackSequence = 0
            while (rows.next()) {
                val sequence = rows.getShort("KEY_SEQ").toInt()
                    .takeIf { !rows.wasNull() && it > 0 }
                    ?: ++fallbackSequence
                keys += sequence to rows.string("COLUMN_NAME")
            }
        }
        return keys.sortedBy(Pair<Int, String>::first).map(Pair<Int, String>::second)
    }

    private fun readForeignKeys(
        metadata: DatabaseMetaData,
        table: DatabaseTableSnapshot,
    ): List<DatabaseForeignKeySnapshot> {
        val keys = mutableListOf<DatabaseForeignKeySnapshot>()
        metadata.getImportedKeys(table.catalog, table.schema, table.name).use { rows ->
            while (rows.next()) {
                keys += DatabaseForeignKeySnapshot(
                    name = rows.stringOrNull("FK_NAME"),
                    columnName = rows.string("FKCOLUMN_NAME"),
                    referencedCatalog = rows.stringOrNull("PKTABLE_CAT"),
                    referencedSchema = rows.stringOrNull("PKTABLE_SCHEM"),
                    referencedTableName = rows.string("PKTABLE_NAME"),
                    referencedColumnName = rows.string("PKCOLUMN_NAME"),
                    updateRule = rows.getShort("UPDATE_RULE").toInt(),
                    deleteRule = rows.getShort("DELETE_RULE").toInt(),
                    sequence = rows.getShort("KEY_SEQ").toInt(),
                )
            }
        }
        return keys.sortedWith(
            compareBy<DatabaseForeignKeySnapshot> { it.name.orEmpty() }
                .thenBy(DatabaseForeignKeySnapshot::sequence),
        )
    }

    private fun readIndexes(
        metadata: DatabaseMetaData,
        table: DatabaseTableSnapshot,
    ): List<DatabaseIndexSnapshot> {
        val columns = linkedMapOf<String, MutableList<Pair<Int, String>>>()
        val unique = linkedMapOf<String, Boolean>()
        runCatching {
            metadata.getIndexInfo(table.catalog, table.schema, table.name, false, false).use { rows ->
                while (rows.next()) {
                    val name = rows.stringOrNull("INDEX_NAME") ?: continue
                    val column = rows.stringOrNull("COLUMN_NAME") ?: continue
                    columns.getOrPut(name) { mutableListOf() } +=
                        rows.getShort("ORDINAL_POSITION").toInt() to column
                    unique[name] = !rows.getBoolean("NON_UNIQUE")
                }
            }
        }
        return columns.map { (name, entries) ->
            DatabaseIndexSnapshot(
                name = name,
                unique = unique[name] == true,
                columns = entries.sortedBy { it.first }.map { it.second },
            )
        }.sortedBy(DatabaseIndexSnapshot::name)
    }

    private fun databaseProduct(
        metadata: DatabaseMetaData,
        configuration: DatabaseConnectionConfiguration,
    ) = DatabaseProductSnapshot(
        name = metadata.databaseProductName.orEmpty(),
        version = metadata.databaseProductVersion.orEmpty(),
        driverName = metadata.driverName.orEmpty(),
        driverVersion = metadata.driverVersion.orEmpty(),
        urlFingerprint = CanonicalDiscoveryJson.sha256(configuration.url).take(16),
    )

    private fun qualifiedSqlIdentifier(
        metadata: DatabaseMetaData,
        schema: String?,
        table: String,
    ): String = listOfNotNull(schema?.takeIf(String::isNotBlank), table)
        .joinToString(".") { quotedSqlIdentifier(metadata, it) }

    private fun quotedSqlIdentifier(
        metadata: DatabaseMetaData,
        identifier: String,
    ): String {
        check(PORTABLE_IDENTIFIER.matches(identifier)) { "Unsafe database identifier." }
        val quote = metadata.identifierQuoteString.orEmpty().trim()
        if (quote.isBlank()) return identifier
        return quote + identifier.replace(quote, quote + quote) + quote
    }

    private fun driverClassFor(url: String): String? = when {
        url.startsWith("jdbc:postgresql:", ignoreCase = true) -> "org.postgresql.Driver"
        url.startsWith("jdbc:mysql:", ignoreCase = true) -> "com.mysql.cj.jdbc.Driver"
        url.startsWith("jdbc:mariadb:", ignoreCase = true) -> "org.mariadb.jdbc.Driver"
        url.startsWith("jdbc:sqlserver:", ignoreCase = true) -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"
        url.startsWith("jdbc:oracle:", ignoreCase = true) -> "oracle.jdbc.OracleDriver"
        url.startsWith("jdbc:hsqldb:", ignoreCase = true) -> "org.hsqldb.jdbc.JDBCDriver"
        url.startsWith("jdbc:h2:", ignoreCase = true) -> "org.h2.Driver"
        url.startsWith("jdbc:db2:", ignoreCase = true) -> "com.ibm.db2.jcc.DB2Driver"
        else -> null
    }

    private fun databaseQualifierMatches(
        configured: String?,
        discovered: String?,
        connectionDefault: String?,
    ): Boolean {
        val explicit = configured?.trim()?.takeIf(String::isNotBlank)
        if (explicit != null) {
            return discovered?.equals(explicit, ignoreCase = true) == true
        }
        val connectionQualifier = connectionDefault?.trim()?.takeIf(String::isNotBlank)
            ?: return true
        return discovered == null || discovered.equals(connectionQualifier, ignoreCase = true)
    }

    companion object {
        private const val MAX_IDENTIFIER_LENGTH = 256
        private const val MAX_QUALIFIED_DATABASE_NAME_LENGTH = MAX_IDENTIFIER_LENGTH * 3 + 2
        private const val MAX_QUALIFIED_NAME_LENGTH = 1_024
        private const val MAX_SEARCH_LENGTH = 120
        private const val MAX_METADATA_NAMES = 500
        private const val MAX_BROWSE_TABLES = 1_000
        private const val MAX_IMPORT_SELECTION = 50
        private const val MAX_IMPORT_TABLES = 100
        private const val MAX_COMPOSITE_IDENTIFIER_COLUMNS = 32
        private const val MAX_PROFILE_LABEL_LENGTH = 120
        private const val MAX_PLACEHOLDER_DEPTH = 12
        private val PORTABLE_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        private val QUALIFIED_JVM_NAME =
            Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
        private val JVM_IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        private val DATABASE_IMPORT_PROFILE_ID = Regex("""[a-z][a-z0-9-]{2,63}""")
        private val PROFILE_FILE =
            Regex("""^application-([A-Za-z0-9_.-]+)\.(?:properties|ya?ml)$""")
        private val PLACEHOLDER = Regex("""\$\{([^{}]+)}""")
        fun getInstance(project: Project): DatabaseReverseEngineeringService =
            project.getService(DatabaseReverseEngineeringService::class.java)

        private fun invalidMetadataName(value: String): Boolean =
            value.isBlank() ||
                value.length > MAX_IDENTIFIER_LENGTH ||
                value.any { it.isISOControl() }

        private fun invalidQualifiedMetadataName(value: String): Boolean =
            value.isBlank() ||
                value.length > MAX_QUALIFIED_DATABASE_NAME_LENGTH ||
                value.any { it.isISOControl() }
    }
}

internal object DatabaseTypeMapper {
    fun suggest(
        column: DatabaseColumnSnapshot,
        primaryKey: Boolean,
        foreignKey: DatabaseForeignKeySnapshot?,
        relatedEntity: SchemaEntitySnapshot?,
    ): DatabaseColumnMappingSuggestion {
        if (foreignKey != null && relatedEntity != null) {
            return DatabaseColumnMappingSuggestion(
                attributeName = column.name.toAssociationName(),
                attributeType = AttributeType.ASSOCIATION,
                javaType = relatedEntity.qualifiedName,
                primaryKey = primaryKey,
                mandatory = !column.nullable,
                length = null,
                precision = null,
                scale = null,
                relatedEntity = relatedEntity.qualifiedName,
                joinColumnName = column.name,
                foreignKeyTable = foreignKey.referencedTableName,
                referencedColumnName = foreignKey.referencedColumnName,
            )
        }
        val normalizedType = column.typeName.uppercase(Locale.ROOT)
        val type = when (column.jdbcType) {
            Types.CHAR, Types.NCHAR ->
                if (column.size == 1) AttributeType.CHARACTER else AttributeType.STRING
            Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR,
            Types.CLOB, Types.NCLOB, Types.SQLXML -> AttributeType.STRING
            Types.TINYINT, Types.SMALLINT, Types.INTEGER -> AttributeType.INTEGER
            Types.BIGINT -> AttributeType.LONG
            Types.FLOAT, Types.REAL, Types.DOUBLE -> AttributeType.DOUBLE
            Types.NUMERIC, Types.DECIMAL -> when {
                primaryKey && column.scale == 0 && (column.size ?: Int.MAX_VALUE) <= 9 ->
                    AttributeType.INTEGER
                primaryKey && column.scale == 0 && (column.size ?: Int.MAX_VALUE) <= 19 ->
                    AttributeType.LONG
                primaryKey &&
                    normalizedType == "NUMBER" &&
                    column.scale == 0 &&
                    column.size == 38 ->
                    // Liquibase maps BIGINT to NUMBER(38,0) on Oracle. Jmix supports
                    // scalar entity identifiers through Long rather than BigInteger,
                    // so retain the source Jmix identifier contract for this exact
                    // Oracle/Jmix convention. Non-identifier NUMBER(38,0) columns
                    // remain BigDecimal and are never narrowed by this rule.
                    AttributeType.LONG
                else -> AttributeType.BIG_DECIMAL
            }
            Types.BOOLEAN, Types.BIT -> AttributeType.BOOLEAN
            Types.DATE -> AttributeType.LOCAL_DATE
            Types.TIME -> AttributeType.LOCAL_TIME
            Types.TIME_WITH_TIMEZONE -> AttributeType.OFFSET_TIME
            Types.TIMESTAMP -> AttributeType.LOCAL_DATE_TIME
            Types.TIMESTAMP_WITH_TIMEZONE -> AttributeType.OFFSET_DATE_TIME
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> AttributeType.BYTE_ARRAY
            Types.OTHER -> if ("UUID" in normalizedType) AttributeType.UUID else AttributeType.CUSTOM
            else -> AttributeType.CUSTOM
        }
        val javaType = when (type) {
            AttributeType.CHARACTER -> "java.lang.Character"
            AttributeType.STRING -> "java.lang.String"
            AttributeType.INTEGER -> "java.lang.Integer"
            AttributeType.LONG -> "java.lang.Long"
            AttributeType.DOUBLE -> "java.lang.Double"
            AttributeType.BIG_DECIMAL -> "java.math.BigDecimal"
            AttributeType.BOOLEAN -> "java.lang.Boolean"
            AttributeType.LOCAL_DATE -> "java.time.LocalDate"
            AttributeType.LOCAL_TIME -> "java.time.LocalTime"
            AttributeType.OFFSET_TIME -> "java.time.OffsetTime"
            AttributeType.LOCAL_DATE_TIME -> "java.time.LocalDateTime"
            AttributeType.OFFSET_DATE_TIME -> "java.time.OffsetDateTime"
            AttributeType.BYTE_ARRAY -> "byte[]"
            AttributeType.UUID -> "java.util.UUID"
            AttributeType.CUSTOM -> "java.lang.Object"
            else -> "java.lang.Object"
        }
        return DatabaseColumnMappingSuggestion(
            attributeName = column.name.toCamelCase(),
            attributeType = type,
            javaType = javaType,
            primaryKey = primaryKey,
            mandatory = !column.nullable,
            length = column.size.takeIf {
                type == AttributeType.STRING && it != null && it > 0
            },
            precision = column.size.takeIf { type == AttributeType.BIG_DECIMAL },
            scale = column.scale.takeIf { type == AttributeType.BIG_DECIMAL },
            customSqlType = column.typeName.takeIf { type == AttributeType.CUSTOM },
            unsupportedReason = if (type == AttributeType.CUSTOM) {
                "JDBC type ${column.jdbcType} (${column.typeName}) needs an explicit Java/custom datatype mapping."
            } else {
                null
            },
            foreignKeyTable = foreignKey?.referencedTableName,
            referencedColumnName = foreignKey?.referencedColumnName,
        )
    }
}

data class DatabaseEntityTableInspectionRequest(
    val storeId: String,
    val tableName: String,
    val schemaName: String? = null,
    val catalogName: String? = null,
    val expectedEntityQualifiedName: String? = null,
    val connectTimeoutSeconds: Int = 10,
    val networkTimeoutSeconds: Int = 30,
)

data class DatabaseEntityTableBrowseRequest(
    val storeId: String,
    val catalogName: String? = null,
    val schemaName: String? = null,
    val search: String = "",
    val includeViews: Boolean = true,
    val limit: Int = 500,
    val connectTimeoutSeconds: Int = 10,
    val networkTimeoutSeconds: Int = 30,
)

data class DatabaseEntityTableBrowseResponse(
    val accepted: Boolean,
    val storeId: String?,
    val database: DatabaseProductSnapshot?,
    val activeCatalog: String?,
    val catalogs: List<String>,
    val schemas: List<DatabaseSchemaReference>,
    val tables: List<DatabaseTableReference>,
    val truncated: Boolean,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun failure(code: String, message: String) =
            DatabaseEntityTableBrowseResponse(
                accepted = false,
                storeId = null,
                database = null,
                activeCatalog = null,
                catalogs = emptyList(),
                schemas = emptyList(),
                tables = emptyList(),
                truncated = false,
                issues = listOf(WorkspaceChangeIssue(code, message)),
            )
    }
}

data class DatabaseSchemaReference(
    val catalog: String?,
    val name: String,
)

data class DatabaseTableReference(
    val catalog: String?,
    val schema: String?,
    val name: String,
    val type: String,
    val remarks: String?,
)

data class DatabaseEntityTableInspectionResponse(
    val accepted: Boolean,
    val snapshotDigest: String?,
    val storeId: String?,
    val database: DatabaseProductSnapshot?,
    val table: DatabaseTableSnapshot?,
    val existingEntityQualifiedName: String?,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun failure(code: String, message: String) =
            DatabaseEntityTableInspectionResponse(
                false,
                null,
                null,
                null,
                null,
                null,
                listOf(WorkspaceChangeIssue(code, message)),
            )
    }
}

data class DatabaseProductSnapshot(
    val name: String,
    val version: String,
    val driverName: String,
    val driverVersion: String,
    val urlFingerprint: String,
)

data class DatabaseTableSnapshot(
    val catalog: String?,
    val schema: String?,
    val name: String,
    val type: String,
    val remarks: String?,
    val columns: List<DatabaseColumnSnapshot> = emptyList(),
    val primaryKeyColumns: List<String> = emptyList(),
    val foreignKeys: List<DatabaseForeignKeySnapshot> = emptyList(),
    val indexes: List<DatabaseIndexSnapshot> = emptyList(),
    val dependencyTables: List<String> = emptyList(),
)

data class DatabaseColumnSnapshot(
    val name: String,
    val jdbcType: Int,
    val typeName: String,
    val size: Int?,
    val scale: Int?,
    val nullable: Boolean,
    val defaultValue: String?,
    val remarks: String?,
    val autoIncrement: Boolean,
    val generated: Boolean,
    val ordinal: Int,
    val primaryKey: Boolean = false,
    val alreadyMapped: Boolean = false,
    val suggestion: DatabaseColumnMappingSuggestion = DatabaseColumnMappingSuggestion(),
)

data class DatabaseColumnMappingSuggestion(
    val attributeName: String = "",
    val attributeType: AttributeType = AttributeType.CUSTOM,
    val javaType: String = "java.lang.Object",
    val primaryKey: Boolean = false,
    val mandatory: Boolean = false,
    val length: Int? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    val customSqlType: String? = null,
    val unsupportedReason: String? = null,
    val relatedEntity: String? = null,
    val joinColumnName: String? = null,
    val foreignKeyTable: String? = null,
    val referencedColumnName: String? = null,
)

data class DatabaseForeignKeySnapshot(
    val name: String?,
    val columnName: String,
    val referencedCatalog: String?,
    val referencedSchema: String?,
    val referencedTableName: String,
    val referencedColumnName: String,
    val updateRule: Int,
    val deleteRule: Int,
    val sequence: Int,
)

data class DatabaseIndexSnapshot(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

data class DatabaseEntityTypeExpansionVerification(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val evidenceDigest: String?,
    val database: DatabaseProductSnapshot?,
    val deployedSchemaName: String?,
    val deployedTableName: String?,
    val originalColumn: DatabaseColumnSnapshot?,
    val shadowColumn: DatabaseColumnSnapshot?,
    val inconsistentBackfillRows: Long?,
) {
    companion object {
        fun failure(
            code: String,
            message: String,
            inconsistentBackfillRows: Long? = null,
        ) = DatabaseEntityTypeExpansionVerification(
            accepted = false,
            code = code,
            message = message,
            evidenceDigest = null,
            database = null,
            deployedSchemaName = null,
            deployedTableName = null,
            originalColumn = null,
            shadowColumn = null,
            inconsistentBackfillRows = inconsistentBackfillRows,
        )
    }
}

private data class DatabaseConnectionConfiguration(
    val url: String,
    val username: String?,
    val password: CharArray?,
    val driverClassName: String,
)

private data class DatabaseReadOnlyContext(
    val store: SchemaDataStoreSnapshot,
    val workspace: SchemaWorkspaceResponse,
    val configuration: DatabaseConnectionConfiguration,
)

private data class DatabaseReadOnlyResult<T>(
    val value: T?,
    val issue: WorkspaceChangeIssue?,
) {
    companion object {
        fun <T> failure(code: String, message: String) =
            DatabaseReadOnlyResult<T>(
                value = null,
                issue = WorkspaceChangeIssue(code, message),
            )
    }
}

private data class DatabaseImportQueueEntry(
    val reference: DatabaseTableReference,
    val selectedByUser: Boolean,
    val requiredBy: Set<String>,
)

private data class DatabaseImportMutableTable(
    val table: DatabaseTableSnapshot,
    var selectedByUser: Boolean,
    val requiredBy: MutableSet<String>,
    val existingEntity: SchemaEntitySnapshot?,
)

internal object DatabaseSqlTypeCompatibility {
    fun accepts(expectedSqlType: String, live: DatabaseColumnSnapshot): Boolean {
        val expected = expectedSqlType.uppercase(Locale.ROOT)
            .replace(Regex("""\s+"""), "")
        val typeName = live.typeName.uppercase(Locale.ROOT)
            .replace(Regex("""\s+"""), "")
        val dimensions = Regex(
            """^[A-Z0-9_ ]+\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)$""",
        )
            .matchEntire(expectedSqlType.trim().uppercase(Locale.ROOT))
        val expectedSize = dimensions?.groupValues?.get(1)?.toIntOrNull()
        val expectedScale = dimensions?.groupValues?.getOrNull(2)?.toIntOrNull()
        return when {
            expected == "BIGINT" ->
                live.jdbcType == Types.BIGINT || typeName in setOf("BIGINT", "INT8")
            expected in setOf("DOUBLE", "DOUBLEPRECISION") ->
                live.jdbcType in setOf(Types.DOUBLE, Types.FLOAT, Types.REAL) ||
                    typeName in setOf("DOUBLE", "DOUBLEPRECISION", "FLOAT8")
            expected.startsWith("DECIMAL(") || expected.startsWith("NUMERIC(") ->
                live.jdbcType in setOf(Types.DECIMAL, Types.NUMERIC) &&
                    expectedSize != null &&
                    live.size != null &&
                    live.size >= expectedSize &&
                    (expectedScale == null || live.scale == expectedScale)
            expected.startsWith("VARCHAR(") || expected.startsWith("CHARACTERVARYING(") ->
                live.jdbcType in setOf(
                    Types.VARCHAR,
                    Types.NVARCHAR,
                    Types.LONGVARCHAR,
                    Types.LONGNVARCHAR,
                ) &&
                    expectedSize != null &&
                    live.size != null &&
                    live.size >= expectedSize
            else -> false
        }
    }
}

internal object DatabaseBackfillVerificationSql {
    fun query(
        qualifiedTable: String,
        originalColumn: String,
        shadowColumn: String,
    ): String =
        "SELECT COUNT(*) FROM $qualifiedTable WHERE $originalColumn IS NOT NULL " +
            "AND ($shadowColumn IS NULL OR $shadowColumn <> $originalColumn)"
}

internal fun redactDatabaseError(error: Throwable): String {
    val root = generateSequence(error) { it.cause }.last()
    val message = root.message.orEmpty()
        .replace(Regex("""(?i)jdbc:[^\s]+"""), "jdbc:<redacted>")
        .replace(Regex("""(?i)(password|pwd)\s*=\s*[^;\s]+"""), "$1=<redacted>")
        .replace(Regex("""(?i)(user|username)\s*=\s*[^;\s]+"""), "$1=<redacted>")
        .take(400)
    return if (message.isBlank()) {
        "Database inspection failed (${root.javaClass.simpleName})."
    } else {
        "Database inspection failed: $message"
    }
}

private fun String.toCamelCase(): String {
    val parts = lowercase(Locale.ROOT).split(Regex("""[^a-z0-9]+""")).filter(String::isNotBlank)
    val candidate = parts.drop(1).fold(parts.firstOrNull().orEmpty()) { result, part ->
        result + part.replaceFirstChar(Char::uppercaseChar)
    }.ifBlank { "attribute" }
    return if (candidate.firstOrNull()?.isDigit() == true) "_$candidate" else candidate
}

private fun String.toAssociationName(): String {
    val withoutIdentifierSuffix = replace(Regex("""(?i)_ID$"""), "")
    return withoutIdentifierSuffix.toCamelCase()
}

private fun ResultSet.string(column: String): String =
    getString(column) ?: ""

private fun ResultSet.stringOrNull(column: String): String? =
    runCatching { getString(column) }.getOrNull()?.takeIf(String::isNotBlank)

private fun DatabaseTableReference.qualifiedName(): String =
    listOfNotNull(catalog, schema, name).joinToString(".")

package org.jmixworkbench.services

import com.intellij.openapi.components.Service
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
) {
    fun inspectEntityTable(
        request: DatabaseEntityTableInspectionRequest,
    ): DatabaseEntityTableInspectionResponse {
        val workspace = SchemaWorkspaceService.getInstance(project).load()
        val store = workspace.stores.firstOrNull { it.id == request.storeId }
            ?: return DatabaseEntityTableInspectionResponse.failure(
                "JVW-DB-STORE-MISSING",
                "The selected Jmix data store no longer exists.",
            )
        val tableName = request.tableName.trim()
        if (tableName.isBlank() || tableName.length > MAX_IDENTIFIER_LENGTH) {
            return DatabaseEntityTableInspectionResponse.failure(
                "JVW-DB-TABLE-NAME-INVALID",
                "Enter a valid database table or view name.",
            )
        }
        val configuration = resolveConnectionConfiguration(store)
            ?: return DatabaseEntityTableInspectionResponse.failure(
                "JVW-DB-CONNECTION-CONFIG-MISSING",
                "No complete ${store.name}.datasource URL and JDBC driver settings were found in the active project profile.",
            )
        val password = configuration.password
        val properties = connectionProperties(configuration.url, request).apply {
            configuration.username?.let { setProperty("user", it) }
            password?.let { setProperty("password", String(it)) }
        }
        val driverUrls = projectLibraryUrls()
        val loader = URLClassLoader(driverUrls.toTypedArray(), javaClass.classLoader)
        return try {
            val driver = loadDriver(configuration, loader)
                ?: return DatabaseEntityTableInspectionResponse.failure(
                    "JVW-DB-DRIVER-MISSING",
                    "JDBC driver ${configuration.driverClassName} is not available in the synced project libraries.",
                )
            val connection = driver.connect(configuration.url, properties)
                ?: return DatabaseEntityTableInspectionResponse.failure(
                    "JVW-DB-DRIVER-URL-REJECTED",
                    "The configured JDBC driver does not accept the data store URL.",
                )
            connection.use { live ->
                runCatching { live.isReadOnly = true }
                runCatching { live.autoCommit = false }
                runCatching {
                    live.setNetworkTimeout(
                        AppExecutorUtil.getAppExecutorService(),
                        request.networkTimeoutSeconds.coerceIn(1, 120) * 1_000,
                    )
                }
                inspectConnected(
                    live,
                    store,
                    request,
                    workspace,
                    configuration,
                )
            }
        } catch (error: Throwable) {
            DatabaseEntityTableInspectionResponse.failure(
                "JVW-DB-INSPECTION-FAILED",
                redactDatabaseError(error),
            )
        } finally {
            runCatching { loader.close() }
            properties.clear()
            password?.fill('\u0000')
        }
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
            connection.catalog,
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
        val existing = workspace.entities.firstOrNull {
            it.storeName == store.name &&
                it.tableName.equals(table.name, ignoreCase = true)
        }
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
        request: DatabaseEntityTableInspectionRequest,
    ): Properties {
        val connectSeconds = request.connectTimeoutSeconds.coerceIn(1, 60)
        val networkSeconds = request.networkTimeoutSeconds.coerceIn(1, 120)
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
        }.toList()
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
        patterns.forEach { pattern ->
            metadata.getTables(catalog, schema, pattern, arrayOf("TABLE", "VIEW")).use { rows ->
                while (rows.next()) {
                    if (rows.string("TABLE_NAME").equals(requested, ignoreCase = true)) {
                        val table = DatabaseTableSnapshot(
                            catalog = rows.stringOrNull("TABLE_CAT"),
                            schema = rows.stringOrNull("TABLE_SCHEM"),
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

    companion object {
        private const val MAX_IDENTIFIER_LENGTH = 256
        private const val MAX_PLACEHOLDER_DEPTH = 12
        private val PROFILE_FILE =
            Regex("""^application-([A-Za-z0-9_.-]+)\.(?:properties|ya?ml)$""")
        private val PLACEHOLDER = Regex("""\$\{([^{}]+)}""")
        fun getInstance(project: Project): DatabaseReverseEngineeringService =
            project.getService(DatabaseReverseEngineeringService::class.java)
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
            Types.NUMERIC, Types.DECIMAL -> AttributeType.BIG_DECIMAL
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
    val connectTimeoutSeconds: Int = 10,
    val networkTimeoutSeconds: Int = 30,
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

private data class DatabaseConnectionConfiguration(
    val url: String,
    val username: String?,
    val password: CharArray?,
    val driverClassName: String,
)

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

package org.jmixworkbench.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.common.ThreadLeakTracker
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in certification against the real Docker database matrix.
 *
 * The ordinary dual-host suite remains hermetic. The release matrix supplies
 * the `jvw.live.db.*` properties and a resolved project JDBC driver jar, then
 * executes this test against each database. This exercises the production
 * project-property resolver, project-library driver loader, metadata browser,
 * table inspector, and database-first import planner.
 */
class DatabaseReverseEngineeringLiveMatrixTest : HeavyPlatformTestCase() {
    fun testProductionReverseEngineeringAgainstLiveDatabase() {
        val target = LiveDatabaseTarget.fromSystemProperties() ?: return
        ThreadLeakTracker.longRunningThreadCreated(
            ApplicationManager.getApplication(),
            "Cleaner-0",
            "Cleaner-1",
            "Cleaner-2",
            "Cleaner-3",
            "Cleaner-4",
            "Cleaner-5",
        )
        val root = getOrCreateProjectBaseDir()
        lateinit var moduleId: String

        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.first()
            moduleId = module.name
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            if (rootModel.contentRoots.none { it == root }) {
                rootModel.addContentEntry(root)
            }
            val library = rootModel.moduleLibraryTable.createLibrary("jvw-live-${target.id}")
            val libraryModel = library.modifiableModel
            target.driverClasspath.forEach { driverFile ->
                libraryModel.addRoot(VfsUtil.getUrlForLibraryRoot(driverFile), OrderRootType.CLASSES)
            }
            libraryModel.commit()
            rootModel.commit()

            write(root, "build.gradle.kts", """plugins { id("io.jmix") version "2.8.2" }""")
            write(
                root,
                "src/main/resources/application.properties",
                """
                main.datasource.url=${target.url}
                main.datasource.username=${target.username}
                main.datasource.password=${target.password}
                main.datasource.driver-class-name=${target.driverClassName}
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/company/cert/entity/OrgUnit.java",
                """
                package com.company.cert.entity;

                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;

                @JmixEntity
                @Entity(name = "cert_OrgUnit")
                @Table(${target.tableAnnotationArguments("JVW_ORG_UNIT")})
                public class OrgUnit {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private Long id;

                    @Column(name = "CODE", nullable = false, length = 64)
                    private String code;
                }
                """.trimIndent(),
            )
        }

        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val store = workspace.stores.singleOrNull {
            it.moduleId == moduleId && it.name == "main"
        } ?: error("The live main data store was not indexed: ${workspace.stores}")
        val service = DatabaseReverseEngineeringService.getInstance(project)

        val browse = service.browseEntityTables(
            DatabaseEntityTableBrowseRequest(
                storeId = store.id,
                catalogName = target.catalog,
                schemaName = target.schema,
                search = "JVW_",
                includeViews = true,
                limit = 100,
            ),
        )
        assertTrue(browse.accepted, browse.issues.toString())
        val orgReference = browse.tables.singleOrNull {
            it.name.equals("JVW_ORG_UNIT", ignoreCase = true)
        }
        assertNotNull(orgReference, "The production browser did not return JVW_ORG_UNIT: $browse")
        val loanReference = browse.tables.singleOrNull {
            it.name.equals("JVW_LOAN_APPLICATION", ignoreCase = true)
        }
        assertNotNull(loanReference, "The production browser did not return JVW_LOAN_APPLICATION: $browse")

        val orgInspection = service.inspectEntityTable(
            DatabaseEntityTableInspectionRequest(
                storeId = store.id,
                tableName = orgReference.name,
                schemaName = orgReference.schema,
                catalogName = orgReference.catalog,
                expectedEntityQualifiedName = "com.company.cert.entity.OrgUnit",
            ),
        )
        assertTrue(orgInspection.accepted, orgInspection.issues.toString())
        assertEquals("com.company.cert.entity.OrgUnit", orgInspection.existingEntityQualifiedName)
        assertTrue(orgInspection.table?.primaryKeyColumns?.any { it.equals("ID", true) } == true)
        assertTrue(
            orgInspection.table?.indexes?.any {
                it.unique && it.columns.any { column -> column.equals("CODE", true) }
            } == true,
            "The production inspector did not reconstruct the unique business key: $orgInspection",
        )

        val loanInspection = service.inspectEntityTable(
            DatabaseEntityTableInspectionRequest(
                storeId = store.id,
                tableName = loanReference.name,
                schemaName = loanReference.schema,
                catalogName = loanReference.catalog,
            ),
        )
        assertTrue(loanInspection.accepted, loanInspection.issues.toString())
        assertTrue(loanInspection.snapshotDigest?.length == 64)
        val loanTable = loanInspection.table
            ?: error("The production inspector returned no table snapshot: $loanInspection")
        assertTrue(
            loanTable.foreignKeys.any {
                it.columnName.equals("ORG_UNIT_ID", true) &&
                    it.referencedTableName.equals("JVW_ORG_UNIT", true)
            },
            "The production inspector did not reconstruct the relationship: $loanInspection",
        )
        assertTrue(
            loanTable.indexes.any {
                it.columns.any { column -> column.equals("APPLICATION_NO", true) }
            },
            "The production inspector did not reconstruct the application number index: $loanInspection",
        )

        val importPlan = service.planEntityImport(
            DatabaseEntityImportRequest(
                storeId = store.id,
                moduleId = moduleId,
                packageName = "com.company.cert.imported",
                selectedTables = listOf(loanReference),
                includeDependencies = true,
            ),
        )
        assertTrue(importPlan.accepted, importPlan.issues.toString())
        assertTrue(importPlan.ready, importPlan.issues.toString())
        assertTrue(
            importPlan.tables.any {
                it.table.name.equals("JVW_LOAN_APPLICATION", true) && it.generated
            },
            "The live database-first planner did not generate the selected entity: $importPlan",
        )
        assertTrue(
            importPlan.tables.any {
                it.table.name.equals("JVW_ORG_UNIT", true) &&
                    it.status == DatabaseEntityImportStatus.EXISTING_ENTITY
            },
            "The dependency closure did not reuse the indexed existing entity: $importPlan",
        )

        val serialized = Gson().toJson(listOf(browse, orgInspection, loanInspection, importPlan))
        assertFalse(serialized.contains(target.password))
        assertFalse(serialized.contains(target.url))

        target.evidenceFile.parentFile?.mkdirs()
        target.evidenceFile.writeText(
            GsonBuilder().setPrettyPrinting().create().toJson(
                ReverseEngineeringCertificationEvidence(
                    database = target.id,
                    hostLane = target.hostLane,
                    productionService = DatabaseReverseEngineeringService::class.qualifiedName.orEmpty(),
                    projectPropertyResolution = true,
                    projectLibraryDriverLoading = true,
                    catalogSchemaBrowse = true,
                    primaryKeyReconstruction = true,
                    foreignKeyReconstruction = true,
                    uniqueConstraintReconstruction = true,
                    indexReconstruction = true,
                    existingEntityReuse = true,
                    dependencyClosure = true,
                    entityImportPlanning = true,
                    credentialRedaction = true,
                ),
            ) + "\n",
        )
    }

    private data class ReverseEngineeringCertificationEvidence(
        val database: String,
        val hostLane: String,
        val productionService: String,
        val projectPropertyResolution: Boolean,
        val projectLibraryDriverLoading: Boolean,
        val catalogSchemaBrowse: Boolean,
        val primaryKeyReconstruction: Boolean,
        val foreignKeyReconstruction: Boolean,
        val uniqueConstraintReconstruction: Boolean,
        val indexReconstruction: Boolean,
        val existingEntityReuse: Boolean,
        val dependencyClosure: Boolean,
        val entityImportPlanning: Boolean,
        val credentialRedaction: Boolean,
    )

    private data class LiveDatabaseTarget(
        val id: String,
        val url: String,
        val username: String,
        val password: String,
        val driverClassName: String,
        val driverClasspath: List<File>,
        val catalog: String?,
        val schema: String?,
        val hostLane: String,
        val evidenceFile: File,
    ) {
        fun tableAnnotationArguments(tableName: String): String = buildList {
            add("""name = "$tableName"""")
            catalog?.let { add("""catalog = "$it"""") }
            schema?.let { add("""schema = "$it"""") }
        }.joinToString()

        companion object {
            fun fromSystemProperties(): LiveDatabaseTarget? {
                val enabled = System.getProperty("jvw.live.db.enabled")?.toBoolean() == true
                if (!enabled) return null
                fun required(name: String): String =
                    System.getProperty(name)?.takeIf(String::isNotBlank)
                        ?: error("Missing live database certification property $name")
                val classpath = required("jvw.live.db.driverClasspath")
                    .split(File.pathSeparatorChar)
                    .map(::File)
                classpath.forEach {
                    require(it.isFile) { "Live JDBC driver classpath entry does not exist: $it" }
                }
                return LiveDatabaseTarget(
                    id = required("jvw.live.db.id"),
                    url = required("jvw.live.db.url"),
                    username = required("jvw.live.db.username"),
                    password = required("jvw.live.db.password"),
                    driverClassName = required("jvw.live.db.driver"),
                    driverClasspath = classpath,
                    catalog = System.getProperty("jvw.live.db.catalog")?.takeIf(String::isNotBlank),
                    schema = System.getProperty("jvw.live.db.schema")?.takeIf(String::isNotBlank),
                    hostLane = required("jvw.live.db.hostLane"),
                    evidenceFile = File(required("jvw.live.db.evidenceFile")),
                )
            }
        }
    }

    companion object {
        private fun write(
            root: com.intellij.openapi.vfs.VirtualFile,
            path: String,
            content: String,
        ) {
            val file = VfsUtil.createDirectories("${root.path}/${path.substringBeforeLast('/', "")}")
                .findOrCreateChildData(this, path.substringAfterLast('/'))
            VfsUtil.saveText(file, content)
        }
    }
}

package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.testFramework.HeavyPlatformTestCase
import org.jmixworkbench.generator.CrudOrchestrator
import org.jmixworkbench.generator.MigrationGenerator
import org.jmixworkbench.model.ChangeSetModel
import org.jmixworkbench.model.ColumnDef
import org.jmixworkbench.model.DbChange
import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.AttributeModel
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.AssociationConfig
import org.jmixworkbench.model.AssociationCollectionType
import org.jmixworkbench.model.AssociationOwnershipTransfer
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.CascadeType
import org.jmixworkbench.model.EntityGenerationTarget
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.DdlGenerationConfig
import org.jmixworkbench.model.DdlGenerationMode
import org.jmixworkbench.model.MigrationModel
import org.jmixworkbench.model.ProjectConfig
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.IdConfig
import org.jmixworkbench.model.FetchType
import org.jmixworkbench.model.LifecycleCallback
import org.jmixworkbench.model.TraitType
import org.jmixworkbench.model.ValidationModel
import org.jmixworkbench.model.ValidationType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testEntitySourceContractAndInheritedEvidenceRoundTripForJavaAndKotlin() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.first()
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(root)
                rootModel.commit()
            }
            write(
                root,
                "build.gradle.kts",
                """plugins { id("io.jmix") version "2.8.3" }""",
            )
            write(
                root,
                "src/main/java/com/acme/entity/BaseRecord.java",
                """
                package com.acme.entity;

                import io.jmix.core.annotation.TenantId;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.time.OffsetDateTime;
                import java.util.UUID;
                import org.springframework.data.annotation.*;

                @JmixEntity
                @MappedSuperclass
                @EntityListeners(BaseRecordListener.class)
                public abstract class BaseRecord {
                    @Id
                    protected UUID id;

                    @Version
                    protected Integer version;

                    @CreatedBy
                    protected String createdBy;

                    @CreatedDate
                    protected OffsetDateTime createdDate;

                    @LastModifiedBy
                    protected String lastModifiedBy;

                    @LastModifiedDate
                    protected OffsetDateTime lastModifiedDate;

                    @TenantId
                    protected String sysTenantId;

                    @Column(name = "BRANCH_CODE", nullable = false)
                    protected String branchCode;

                    @PrePersist
                    protected void initializeAudit() {}
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/LoanApp.kt",
                """
                package com.acme.entity

                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.math.BigDecimal

                @JmixEntity
                @Entity
                @Table(name = "LOAN_APP")
                @EntityListeners(LoanAppListener::class)
                open class LoanApp : BaseRecord(), Approvable {
                    @Column(name = "AMOUNT", nullable = false)
                    var amount: BigDecimal? = null

                    @PreUpdate
                    protected fun beforeUpdate() {}
                }

                interface Approvable
                """.trimIndent(),
            )
        }

        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val base = workspace.entities.single { it.className == "BaseRecord" }
        val loan = workspace.entities.single { it.className == "LoanApp" }

        assertEquals(
            listOf(TraitType.STANDARD_ENTITY, TraitType.HAS_TENANT_ID),
            base.traits,
        )
        assertEquals(listOf(LifecycleCallback.PRE_PERSIST), base.lifecycleCallbacks)
        assertEquals(
            listOf("com.acme.entity.BaseRecordListener"),
            base.entityListeners,
        )

        assertEquals("com.acme.entity.BaseRecord", loan.extendsClass)
        assertEquals(listOf("com.acme.entity.Approvable"), loan.implementsInterfaces)
        assertEquals(listOf(LifecycleCallback.PRE_UPDATE), loan.lifecycleCallbacks)
        assertEquals(listOf("com.acme.entity.LoanAppListener"), loan.entityListeners)
        assertTrue(
            loan.inheritedAttributes.any {
                it.attribute.name == "branchCode" &&
                    it.declaredBy == "com.acme.entity.BaseRecord" &&
                    it.depth == 1
            },
            loan.inheritedAttributes.toString(),
        )
        assertEquals(
            setOf(TraitType.STANDARD_ENTITY, TraitType.HAS_TENANT_ID),
            loan.inheritedTraits.map { it.trait }.toSet(),
        )
    }

    fun testCustomGradleResourceRootKeepsLiquibaseClasspathAndGenerationDestination() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.first()
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(root)
                rootModel.commit()
            }
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                sourceSets {
                    named("main") {
                        resources {
                            srcDir("runtime-resources")
                        }
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "runtime-resources/application.properties",
                "main.liquibase.change-log=com/acme/liquibase/changelog.xml\n",
            )
            write(
                root,
                "runtime-resources/com/acme/liquibase/changelog.xml",
                """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <includeAll path="com/acme/liquibase/changelog"/>
                </databaseChangeLog>
                """.trimIndent(),
            )
        }

        val service = SchemaWorkspaceService.getInstance(project)
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        assertTrue(
            graph.modules.flatMap(ApplicationGraphModuleCoverage::sourceRoots).any {
                it.relativePath == "runtime-resources" &&
                    it.kind == ApplicationGraphSourceRootKind.RESOURCES
            },
            graph.modules.flatMap(ApplicationGraphModuleCoverage::sourceRoots).toString(),
        )
        assertTrue(
            ProjectSourceDestinationService.getInstance(project)
                .productionResources(graph)
                .any { it.sourceRoot == "runtime-resources" },
        )
        val workspace = service.load()
        val store = workspace.stores.single()

        assertEquals("com/acme/liquibase/changelog.xml", store.configuredPath)
        assertEquals(
            "runtime-resources/com/acme/liquibase/changelog",
            store.generatedDirectory,
        )
        val preview = service.previewMigration(
            SchemaMigrationChangeRequest(store.id, addStatusMigration()),
        )
        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val generated = preview.files.single()
        assertTrue(
            generated.relativePath.startsWith("runtime-resources/com/acme/liquibase/changelog/"),
            generated.relativePath,
        )
        assertTrue(
            generated.resultContent.contains("logicalFilePath=\"com/acme/liquibase/changelog/"),
            generated.resultContent,
        )
    }

    fun testExistingRelationshipMetadataIsRoundTrippedWithoutCardinalityLoss() {
        createRelationshipFixture()
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val loanApp = workspace.entities.single { it.className == "LoanApp" }

        val account = loanApp.attributes.single { it.name == "loanAcct" }
        val accountRelation = requireNotNull(account.associationDetails)
        assertEquals(AssociationType.MANY_TO_ONE, accountRelation.associationType)
        assertEquals("com.acme.entity.LoanAcct", accountRelation.relatedEntity)
        assertEquals("LOAN_ACCT", accountRelation.relatedTableName)
        assertEquals(IdType.LONG, accountRelation.relatedIdType)
        assertEquals("ACCT_ID", accountRelation.relatedIdColumnName)
        assertEquals("LOAN_ACCT_ID", accountRelation.joinColumnName)
        assertEquals(FetchType.EAGER, accountRelation.fetch)
        assertEquals(listOf(CascadeType.MERGE), accountRelation.cascade)
        assertFalse(account.nullable)

        val schedules = loanApp.attributes.single { it.name == "schedules" }
        val scheduleRelation = requireNotNull(schedules.associationDetails)
        assertEquals(AssociationType.ONE_TO_MANY, scheduleRelation.associationType)
        assertEquals("loanApp", scheduleRelation.mappedBy)
        assertEquals(AssociationCollectionType.LIST, scheduleRelation.collectionType)
        assertTrue(scheduleRelation.composition)
        assertTrue(scheduleRelation.orphanRemoval)
        assertEquals("CASCADE", scheduleRelation.onDelete)
        assertEquals(listOf(CascadeType.ALL), scheduleRelation.cascade)

        val documents = loanApp.attributes.single { it.name == "documents" }
        val documentRelation = requireNotNull(documents.associationDetails)
        assertEquals(AssociationType.MANY_TO_MANY, documentRelation.associationType)
        assertEquals(AssociationCollectionType.SET, documentRelation.collectionType)
        assertEquals("LOAN_APP_DOCUMENT_LINK", documentRelation.joinTable?.name)
        assertEquals("APP_ID", documentRelation.joinTable?.joinColumnName)
        assertEquals("DOC_ID", documentRelation.joinTable?.inverseJoinColumnName)

        val fundProfile = loanApp.attributes.single { it.name == "fundProfile" }
        val fundRelation = requireNotNull(fundProfile.associationDetails)
        assertTrue(fundRelation.crossDataStore)
        assertEquals("fundProfileId", fundRelation.localIdAttributeName)
        assertEquals("FUND_PROFILE_ID", fundRelation.joinColumnName)
        assertEquals("com.acme.entity.FundProfile", fundRelation.relatedEntity)
        assertEquals("FUND_PROFILE", fundRelation.relatedTableName)
    }

    fun testExistingJavaAndKotlinRelationshipSemanticsAreSourceSafeAndStructureLocked() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.first()
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(root)
                rootModel.commit()
            }
            write(
                root,
                "src/main/java/com/acme/entity/RelationshipChild.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "RELATIONSHIP_CHILD")
                public class RelationshipChild {
                    @Id
                    private UUID id;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/JavaRelationshipOwner.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.List;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "JAVA_RELATIONSHIP_OWNER")
                public class JavaRelationshipOwner {
                    @Id
                    private UUID id;

                    @CustomRelationshipGuard("manual")
                    @OneToMany(mappedBy = "javaOwner", cascade = {CascadeType.MERGE})
                    private List<RelationshipChild> children;

                    public String manualRelationshipLabel() {
                        return "java-owner";
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinRelationshipOwner.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "KOTLIN_RELATIONSHIP_OWNER")
                open class KotlinRelationshipOwner {
                    @Id
                    var id: UUID? = null

                    @CustomKotlinRelationship
                    @OneToOne(mappedBy = "kotlinOwner")
                    var detail: RelationshipChild? = null

                    fun manualRelationshipLabel(): String = "kotlin-owner"
                }
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val service = ExistingEntityChangeService.getInstance(project)

        fun model(
            snapshot: SchemaEntitySnapshot,
            attributeName: String,
            type: AttributeType,
            cascade: List<CascadeType>,
            fetch: FetchType,
            orphanRemoval: Boolean,
            onDelete: String,
        ): EntityModel {
            val current = snapshot.attributes.single { it.name == attributeName }
            val relation = requireNotNull(current.associationDetails)
            return EntityModel(
                className = snapshot.className,
                packageName = snapshot.qualifiedName.substringBeforeLast('.'),
                sourceLanguage = if (snapshot.sourceLocator.relativePath.endsWith(".kt")) {
                    EntitySourceLanguage.KOTLIN
                } else {
                    EntitySourceLanguage.JAVA
                },
                tableName = snapshot.tableName,
                dataStore = snapshot.storeName,
                attributes = mutableListOf(
                    AttributeModel(
                        name = current.name,
                        type = type,
                        columnName = current.columnName,
                        mandatory = !current.nullable,
                        unique = current.unique,
                        association = AssociationConfig(
                            associationType = relation.associationType,
                            relatedEntity = relation.relatedEntity,
                            relatedTableName = relation.relatedTableName,
                            relatedIdColumnName = relation.relatedIdColumnName,
                            relatedIdType = relation.relatedIdType,
                            localIdAttributeName = relation.localIdAttributeName,
                            mappedBy = relation.mappedBy,
                            joinColumnName = relation.joinColumnName,
                            joinTable = relation.joinTable,
                            cascade = cascade.toMutableList(),
                            fetch = fetch,
                            collectionType = relation.collectionType,
                            crossDataStore = relation.crossDataStore,
                            orphanRemoval = orphanRemoval,
                            onDelete = onDelete,
                        ),
                    ),
                ),
                ddlGeneration = DdlGenerationConfig(false, DdlGenerationMode.DISABLED),
            )
        }

        val javaSnapshot = workspace.entities.single { it.className == "JavaRelationshipOwner" }
        val javaPreview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                javaSnapshot.sourceLocator,
                model(
                    javaSnapshot,
                    "children",
                    AttributeType.COMPOSITION,
                    listOf(CascadeType.ALL, CascadeType.REMOVE),
                    FetchType.EAGER,
                    orphanRemoval = true,
                    onDelete = "CASCADE",
                ),
            ),
        )
        assertTrue(javaPreview.accepted, javaPreview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(1, javaPreview.files.size)
        val java = javaPreview.files.single().resultContent
        assertTrue(java.contains("@CustomRelationshipGuard(\"manual\")"))
        assertTrue(java.contains("mappedBy = \"javaOwner\""))
        assertTrue(java.contains("cascade = {CascadeType.ALL, CascadeType.REMOVE}"))
        assertTrue(java.contains("fetch = FetchType.EAGER"))
        assertTrue(java.contains("orphanRemoval = true"))
        assertTrue(java.contains("@Composition"))
        assertTrue(java.contains("@OnDelete(DeletePolicy.CASCADE)"))
        assertTrue(java.contains("public String manualRelationshipLabel()"))
        assertFalse(javaPreview.files.any { it.relativePath.endsWith(".xml") })

        val kotlinSnapshot = workspace.entities.single { it.className == "KotlinRelationshipOwner" }
        assertEquals(
            FetchType.EAGER,
            kotlinSnapshot.attributes.single { it.name == "detail" }.associationDetails?.fetch,
            "An omitted to-one fetch must reconstruct Jakarta Persistence's EAGER default.",
        )
        val kotlinPreview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                kotlinSnapshot.sourceLocator,
                model(
                    kotlinSnapshot,
                    "detail",
                    AttributeType.ASSOCIATION,
                    listOf(CascadeType.MERGE),
                    FetchType.LAZY,
                    orphanRemoval = true,
                    onDelete = "DENY",
                ),
            ),
        )
        assertTrue(kotlinPreview.accepted, kotlinPreview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(1, kotlinPreview.files.size)
        val kotlin = kotlinPreview.files.single().resultContent
        assertTrue(kotlin.contains("@CustomKotlinRelationship"))
        assertTrue(kotlin.contains("mappedBy = \"kotlinOwner\""))
        assertTrue(kotlin.contains("cascade = [CascadeType.MERGE]"))
        assertTrue(kotlin.contains("fetch = FetchType.LAZY"))
        assertTrue(kotlin.contains("orphanRemoval = true"))
        assertTrue(kotlin.contains("@OnDelete(DeletePolicy.DENY)"))
        assertTrue(kotlin.contains("fun manualRelationshipLabel(): String"))
        assertFalse(kotlinPreview.files.any { it.relativePath.endsWith(".xml") })

        val structuralChange = model(
            javaSnapshot,
            "children",
            AttributeType.ASSOCIATION,
            listOf(CascadeType.MERGE),
            FetchType.LAZY,
            orphanRemoval = false,
            onDelete = "DENY",
        ).let { entity ->
            entity.copy(
                attributes = entity.attributes.map { attribute ->
                    attribute.copy(
                        association = attribute.association?.copy(
                            associationType = AssociationType.MANY_TO_MANY,
                        ),
                    )
                }.toMutableList(),
            )
        }
        val rejected = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(javaSnapshot.sourceLocator, structuralChange),
        )
        assertFalse(rejected.accepted)
        assertTrue(rejected.issues.any { it.code == "JVW-ENTITY-RELATIONSHIP-SHAPE-REQUIRES-IMPACT" })
    }

    fun testOwningRelationshipNullabilityChangesAreEvidenceCheckedAndReversible() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/Department.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "DEPARTMENT")
                public class Department {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/EmployeeAssignment.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "EMPLOYEE_ASSIGNMENT")
                public class EmployeeAssignment {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;

                    @ManualRelationshipMarker("preserve")
                    @ManyToOne(fetch = FetchType.LAZY)
                    @JoinColumn(name = "DEPARTMENT_ID", referencedColumnName = "ID")
                    private Department department;

                    public String manualAssignmentCode() { return "assignment"; }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/Person.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "PERSON")
                open class Person {
                    @Id
                    @Column(name = "ID", nullable = false)
                    var id: UUID? = null
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/PersonBadge.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "PERSON_BADGE")
                open class PersonBadge {
                    @Id
                    @Column(name = "ID", nullable = false)
                    var id: UUID? = null

                    @ManualKotlinRelationship
                    @OneToOne(fetch = FetchType.LAZY, optional = false)
                    @JoinColumn(name = "PERSON_ID", referencedColumnName = "ID", nullable = false, unique = true)
                    var person: Person? = null

                    fun manualBadgeCode(): String = "badge"
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog/014-relationship-nullability.xml",
                """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <changeSet id="relationship-nullability" author="team">
                        <createTable tableName="DEPARTMENT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                        </createTable>
                        <createTable tableName="EMPLOYEE_ASSIGNMENT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="DEPARTMENT_ID" type="UUID"/>
                        </createTable>
                        <addForeignKeyConstraint
                            constraintName="FK_EMPLOYEE_ASSIGNMENT_DEPARTMENT"
                            baseTableName="EMPLOYEE_ASSIGNMENT"
                            baseColumnNames="DEPARTMENT_ID"
                            referencedTableName="DEPARTMENT"
                            referencedColumnNames="ID"/>
                        <createTable tableName="PERSON">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                        </createTable>
                        <createTable tableName="PERSON_BADGE">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="PERSON_ID" type="UUID">
                                <constraints nullable="false"/>
                            </column>
                        </createTable>
                        <addUniqueConstraint
                            tableName="PERSON_BADGE"
                            constraintName="UQ_PERSON_BADGE_PERSON"
                            columnNames="PERSON_ID"/>
                        <addForeignKeyConstraint
                            constraintName="FK_PERSON_BADGE_PERSON"
                            baseTableName="PERSON_BADGE"
                            baseColumnNames="PERSON_ID"
                            referencedTableName="PERSON"
                            referencedColumnNames="ID"/>
                    </changeSet>
                </databaseChangeLog>
                """.trimIndent(),
            )
        }

        val schemaService = SchemaWorkspaceService.getInstance(project)
        val service = ExistingEntityChangeService.getInstance(project)
        val changeService = WorkspaceChangeService.getInstance(project)
        val initialWorkspace = schemaService.load(forceRefresh = true)
        val store = initialWorkspace.stores.single()

        fun model(
            snapshot: SchemaEntitySnapshot,
            attributeName: String,
            mandatory: Boolean,
            joinColumnName: String? = null,
        ): EntityModel {
            val current = snapshot.attributes.single { it.name == attributeName }
            val relation = requireNotNull(current.associationDetails)
            return EntityModel(
                className = snapshot.className,
                packageName = snapshot.qualifiedName.substringBeforeLast('.'),
                sourceLanguage = if (snapshot.sourceLocator.relativePath.endsWith(".kt")) {
                    EntitySourceLanguage.KOTLIN
                } else {
                    EntitySourceLanguage.JAVA
                },
                tableName = snapshot.tableName,
                dataStore = snapshot.storeName,
                generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
                attributes = mutableListOf(
                    AttributeModel(
                        name = current.name,
                        type = AttributeType.ASSOCIATION,
                        columnName = current.columnName,
                        mandatory = mandatory,
                        unique = current.unique,
                        association = AssociationConfig(
                            associationType = relation.associationType,
                            relatedEntity = relation.relatedEntity,
                            relatedTableName = relation.relatedTableName,
                            relatedIdColumnName = relation.relatedIdColumnName,
                            relatedIdType = relation.relatedIdType,
                            localIdAttributeName = relation.localIdAttributeName,
                            mappedBy = relation.mappedBy,
                            joinColumnName = joinColumnName ?: relation.joinColumnName,
                            joinTable = relation.joinTable,
                            cascade = relation.cascade.toMutableList(),
                            fetch = relation.fetch,
                            collectionType = relation.collectionType,
                            crossDataStore = relation.crossDataStore,
                            orphanRemoval = relation.orphanRemoval,
                            onDelete = relation.onDelete,
                        ),
                    ),
                ),
                ddlGeneration = DdlGenerationConfig(true, DdlGenerationMode.CREATE_AND_DROP),
            )
        }

        val assignment = initialWorkspace.entities.single { it.className == "EmployeeAssignment" }
        val requiredRequest = ExistingEntityAttributeAdditionRequest(
            assignment.sourceLocator,
            model(assignment, "department", mandatory = true),
        )
        val requiredPreview = service.previewAttributeAdditions(requiredRequest)
        assertTrue(
            requiredPreview.accepted,
            requiredPreview.issues.joinToString { "${it.code}: ${it.message}" },
        )
        assertEquals(2, requiredPreview.files.size)
        val requiredJava = requiredPreview.files.single {
            it.relativePath.endsWith("/EmployeeAssignment.java")
        }.resultContent
        val requiredMigration = requiredPreview.files.single {
            it.relativePath.endsWith(".xml")
        }.resultContent
        assertTrue(requiredJava.contains("@ManualRelationshipMarker(\"preserve\")"))
        assertTrue(requiredJava.contains("optional = false"))
        assertTrue(requiredJava.contains("nullable = false"))
        assertTrue(requiredJava.contains("referencedColumnName = \"ID\""))
        assertTrue(requiredJava.contains("manualAssignmentCode"))
        assertTrue(requiredMigration.contains("""<columnExists tableName="EMPLOYEE_ASSIGNMENT" columnName="DEPARTMENT_ID""""))
        assertTrue(requiredMigration.contains("WHERE DEPARTMENT_ID IS NULL"))
        assertTrue(requiredMigration.contains("""<addNotNullConstraint tableName="EMPLOYEE_ASSIGNMENT" columnName="DEPARTMENT_ID""""))
        assertTrue(requiredMigration.contains("""<dropNotNullConstraint tableName="EMPLOYEE_ASSIGNMENT" columnName="DEPARTMENT_ID""""))
        assertTrue(requiredMigration.contains("<rollback>"))

        val qualifiedRejected = service.previewAttributeAdditions(
            requiredRequest.copy(
                entity = requiredRequest.entity.copy(tableSchema = "PUBLIC"),
            ),
        )
        assertFalse(qualifiedRejected.accepted)
        assertTrue(
            qualifiedRejected.issues.any {
                it.code == "JVW-ENTITY-RELATIONSHIP-NULLABILITY-QUALIFIED-TABLE"
            },
            qualifiedRejected.issues.toString(),
        )

        val requiredPrepared = service.prepareAttributeAdditions(
            ExistingEntityAttributeAdditionApplyRequest(
                change = requiredRequest,
                expectedPlanDigest = requireNotNull(requiredPreview.planDigest),
            ),
        )
        val requiredApplied = changeService.applyPrepared(requiredPrepared)
        assertTrue(
            requiredApplied.success,
            requiredApplied.issues.joinToString { "${it.code}: ${it.message}" },
        )
        val afterRequired = schemaService.load(forceRefresh = true)
        assertFalse(
            afterRequired.entities.single { it.className == "EmployeeAssignment" }
                .attributes.single { it.name == "department" }.nullable,
        )
        assertFalse(
            afterRequired.physicalSchemas.single { it.storeId == store.id }
                .tables.single { it.name == "EMPLOYEE_ASSIGNMENT" }
                .columns.single { it.name == "DEPARTMENT_ID" }.nullable,
        )

        val badge = afterRequired.entities.single { it.className == "PersonBadge" }
        val optionalRequest = ExistingEntityAttributeAdditionRequest(
            badge.sourceLocator,
            model(
                snapshot = badge,
                attributeName = "person",
                mandatory = false,
                joinColumnName = "BADGE_OWNER_ID",
            ),
        )
        val optionalPreview = service.previewAttributeAdditions(optionalRequest)
        assertTrue(
            optionalPreview.accepted,
            optionalPreview.issues.joinToString { "${it.code}: ${it.message}" },
        )
        assertEquals(2, optionalPreview.files.size)
        val optionalKotlin = optionalPreview.files.single {
            it.relativePath.endsWith("/PersonBadge.kt")
        }.resultContent
        val optionalMigration = optionalPreview.files.single {
            it.relativePath.endsWith(".xml")
        }.resultContent
        assertTrue(optionalKotlin.contains("@ManualKotlinRelationship"))
        assertFalse(optionalKotlin.contains("optional = false"))
        assertFalse(optionalKotlin.contains("nullable = false, unique = true"))
        assertTrue(optionalKotlin.contains("unique = true"))
        assertTrue(optionalKotlin.contains("""name = "BADGE_OWNER_ID""""))
        assertTrue(optionalKotlin.contains("referencedColumnName = \"ID\""))
        assertTrue(optionalKotlin.contains("manualBadgeCode"))
        assertTrue(optionalMigration.contains("""<columnExists tableName="PERSON_BADGE" columnName="PERSON_ID""""))
        assertTrue(optionalMigration.contains("""oldColumnName="PERSON_ID" newColumnName="BADGE_OWNER_ID""""))
        assertTrue(optionalMigration.contains("""<dropNotNullConstraint tableName="PERSON_BADGE" columnName="BADGE_OWNER_ID""""))
        assertTrue(optionalMigration.contains("""<addNotNullConstraint tableName="PERSON_BADGE" columnName="BADGE_OWNER_ID""""))
        assertTrue(optionalMigration.contains("""oldColumnName="BADGE_OWNER_ID" newColumnName="PERSON_ID""""))
        assertTrue(optionalMigration.contains("<rollback>"))

        val optionalPrepared = service.prepareAttributeAdditions(
            ExistingEntityAttributeAdditionApplyRequest(
                change = optionalRequest,
                expectedPlanDigest = requireNotNull(optionalPreview.planDigest),
            ),
        )
        val optionalApplied = changeService.applyPrepared(optionalPrepared)
        assertTrue(
            optionalApplied.success,
            optionalApplied.issues.joinToString { "${it.code}: ${it.message}" },
        )
        val afterOptional = schemaService.load(forceRefresh = true)
        assertTrue(
            afterOptional.entities.single { it.className == "PersonBadge" }
                .attributes.single { it.name == "person" }.nullable,
        )
        assertTrue(
            afterOptional.physicalSchemas.single { it.storeId == store.id }
                .tables.single { it.name == "PERSON_BADGE" }
                .columns.single { it.name == "BADGE_OWNER_ID" }.nullable,
        )
        assertFalse(
            afterOptional.physicalSchemas.single { it.storeId == store.id }
                .tables.single { it.name == "PERSON_BADGE" }
                .columns.any { it.name == "PERSON_ID" },
        )
    }

    fun testBidirectionalRelationshipGenerationUpdatesJavaKotlinAndSelfReferencesAtomically() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/JavaAssignment.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "JAVA_ASSIGNMENT")
                public class JavaAssignment {
                    @Id
                    private UUID id;
                    public String manualLabel() { return "assignment"; }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinWorker.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "KOTLIN_WORKER")
                open class KotlinWorker {
                    @Id
                    var id: UUID? = null
                    @Column(name = "WORKER_CODE", nullable = false)
                    var workerCode: String? = null
                    fun manualLabel(): String = workerCode ?: "worker"
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/OrgUnit.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "ORG_UNIT")
                public class OrgUnit {
                    @Id
                    private UUID id;
                    public String manualPath() { return "root"; }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/EstablishedDepartment.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "ESTABLISHED_DEPARTMENT")
                open class EstablishedDepartment {
                    @Id
                    var id: UUID? = null
                    fun manualDepartmentCode(): String = "department"
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/EstablishedWorker.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "ESTABLISHED_WORKER")
                public class EstablishedWorker {
                    @Id
                    private UUID id;

                    @ManyToOne(fetch = FetchType.LAZY)
                    @JoinColumn(name = "DEPARTMENT_ID")
                    private EstablishedDepartment department;

                    public String manualWorkerCode() { return "worker"; }
                }
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val store = workspace.stores.single()
        val javaSource = workspace.entities.single { it.className == "JavaAssignment" }
        val kotlinTarget = workspace.entities.single { it.className == "KotlinWorker" }
        val service = ExistingEntityChangeService.getInstance(project)
        val sourceModel = EntityModel(
            className = javaSource.className,
            packageName = javaSource.qualifiedName.substringBeforeLast('.'),
            tableName = javaSource.tableName,
            dataStore = javaSource.storeName,
            generationTarget = EntityGenerationTarget(javaSource.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = "worker",
                    type = AttributeType.ASSOCIATION,
                    mandatory = true,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = kotlinTarget.qualifiedName,
                        relatedTableName = kotlinTarget.tableName,
                        relatedIdColumnName = kotlinTarget.idColumnName,
                        relatedIdType = kotlinTarget.idType,
                        joinColumnName = "WORKER_ID",
                        cascade = mutableListOf(),
                        fetch = FetchType.LAZY,
                        collectionType = AssociationCollectionType.LIST,
                        generateInverse = true,
                        inverseAttributeName = "assignments",
                    ),
                ),
            ),
        )
        val preview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(javaSource.sourceLocator, sourceModel),
        )
        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(3, preview.files.size)
        val java = preview.files.single { it.relativePath.endsWith("JavaAssignment.java") }.resultContent
        val kotlin = preview.files.single { it.relativePath.endsWith("KotlinWorker.kt") }.resultContent
        val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
        assertTrue(java.contains("@ManyToOne(fetch = FetchType.LAZY, optional = false)"))
        assertTrue(java.contains("""@JoinColumn(name = "WORKER_ID""""))
        assertTrue(java.contains("nullable = false"))
        assertTrue(java.contains("KotlinWorker worker;"))
        assertTrue(java.contains("manualLabel"))
        assertTrue(kotlin.contains("@OneToMany("))
        assertTrue(kotlin.contains("mappedBy = \"worker\""))
        assertTrue(kotlin.contains("fetch = FetchType.LAZY"))
        assertTrue(kotlin.contains("assignments"))
        assertTrue(kotlin.contains("manualLabel"))
        assertTrue(kotlin.contains("workerCode"))
        assertTrue(migration.contains("""tableName="JAVA_ASSIGNMENT""""))
        assertTrue(migration.contains("""name="WORKER_ID""""))
        assertTrue(migration.contains("""referencedTableName="KOTLIN_WORKER""""))
        assertFalse(migration.contains("""tableName="KOTLIN_WORKER""""))

        val establishedSource = workspace.entities.single { it.className == "EstablishedWorker" }
        val establishedTarget = workspace.entities.single { it.className == "EstablishedDepartment" }
        val establishedCurrent = establishedSource.attributes.single { it.name == "department" }
        val establishedRelation = requireNotNull(establishedCurrent.associationDetails)
        val establishedModel = EntityModel(
            className = establishedSource.className,
            packageName = establishedSource.qualifiedName.substringBeforeLast('.'),
            tableName = establishedSource.tableName,
            dataStore = establishedSource.storeName,
            generationTarget = EntityGenerationTarget(establishedSource.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = establishedCurrent.name,
                    type = AttributeType.ASSOCIATION,
                    columnName = establishedCurrent.columnName,
                    mandatory = !establishedCurrent.nullable,
                    unique = establishedCurrent.unique,
                    association = AssociationConfig(
                        associationType = establishedRelation.associationType,
                        relatedEntity = establishedRelation.relatedEntity,
                        relatedTableName = establishedRelation.relatedTableName,
                        relatedIdColumnName = establishedRelation.relatedIdColumnName,
                        relatedIdType = establishedRelation.relatedIdType,
                        mappedBy = establishedRelation.mappedBy,
                        joinColumnName = establishedRelation.joinColumnName,
                        joinTable = establishedRelation.joinTable,
                        cascade = establishedRelation.cascade.toMutableList(),
                        fetch = FetchType.EAGER,
                        collectionType = establishedRelation.collectionType,
                        crossDataStore = establishedRelation.crossDataStore,
                        orphanRemoval = establishedRelation.orphanRemoval,
                        onDelete = establishedRelation.onDelete,
                        generateInverse = true,
                        inverseAttributeName = "workers",
                    ),
                ),
            ),
        )
        val establishedPreview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(establishedSource.sourceLocator, establishedModel),
        )
        assertTrue(
            establishedPreview.accepted,
            establishedPreview.issues.joinToString { "${it.code}: ${it.message}" },
        )
        assertEquals(2, establishedPreview.files.size)
        val establishedJava = establishedPreview.files.single {
            it.relativePath.endsWith("EstablishedWorker.java")
        }.resultContent
        val establishedKotlin = establishedPreview.files.single {
            it.relativePath.endsWith("EstablishedDepartment.kt")
        }.resultContent
        assertTrue(establishedJava.contains("@ManyToOne(fetch = FetchType.EAGER)"))
        assertTrue(establishedJava.contains("""@JoinColumn(name = "DEPARTMENT_ID")"""))
        assertTrue(establishedJava.contains("manualWorkerCode"))
        assertTrue(establishedKotlin.contains("@OneToMany("))
        assertTrue(establishedKotlin.contains("mappedBy = \"department\""))
        assertTrue(establishedKotlin.contains("workers"))
        assertTrue(establishedKotlin.contains("manualDepartmentCode"))
        assertFalse(establishedPreview.files.any { it.relativePath.endsWith(".xml") })
        assertEquals(
            establishedTarget.qualifiedName,
            establishedRelation.relatedEntity,
        )
        val inverseOnlyModel = establishedModel.copy(
            attributes = establishedModel.attributes.map { attribute ->
                attribute.copy(
                    association = attribute.association?.copy(
                        fetch = establishedRelation.fetch,
                        inverseAttributeName = "members",
                    ),
                )
            }.toMutableList(),
        )
        val inverseOnlyPreview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(establishedSource.sourceLocator, inverseOnlyModel),
        )
        assertTrue(
            inverseOnlyPreview.accepted,
            inverseOnlyPreview.issues.joinToString { "${it.code}: ${it.message}" },
        )
        assertEquals(1, inverseOnlyPreview.files.size)
        assertTrue(
            inverseOnlyPreview.files.single().relativePath.endsWith("EstablishedDepartment.kt"),
        )
        assertTrue(inverseOnlyPreview.files.single().resultContent.contains("members"))
        assertFalse(inverseOnlyPreview.files.any { it.relativePath.endsWith(".xml") })

        val orgUnit = workspace.entities.single { it.className == "OrgUnit" }
        val selfModel = EntityModel(
            className = orgUnit.className,
            packageName = orgUnit.qualifiedName.substringBeforeLast('.'),
            tableName = orgUnit.tableName,
            dataStore = orgUnit.storeName,
            generationTarget = EntityGenerationTarget(orgUnit.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = "manager",
                    type = AttributeType.ASSOCIATION,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = orgUnit.qualifiedName,
                        relatedTableName = orgUnit.tableName,
                        relatedIdColumnName = orgUnit.idColumnName,
                        relatedIdType = orgUnit.idType,
                        joinColumnName = "MANAGER_ID",
                        cascade = mutableListOf(),
                        fetch = FetchType.LAZY,
                        collectionType = AssociationCollectionType.LIST,
                        generateInverse = true,
                        inverseAttributeName = "directReports",
                    ),
                ),
            ),
        )
        val selfPreview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(orgUnit.sourceLocator, selfModel),
        )
        assertTrue(
            selfPreview.accepted,
            selfPreview.issues.joinToString { "${it.code}: ${it.message}" },
        )
        assertEquals(2, selfPreview.files.size)
        val selfJava = selfPreview.files.single { it.relativePath.endsWith("OrgUnit.java") }.resultContent
        assertTrue(selfJava.contains("OrgUnit manager;"))
        assertTrue(selfJava.contains("@OneToMany("))
        assertTrue(selfJava.contains("mappedBy = \"manager\""))
        assertTrue(selfJava.contains("List<OrgUnit> directReports;"))
        assertTrue(selfJava.contains("manualPath"))

        val collision = sourceModel.copy(
            attributes = sourceModel.attributes.map {
                it.copy(
                    association = it.association?.copy(inverseAttributeName = "workerCode"),
                )
            }.toMutableList(),
        )
        val rejected = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(javaSource.sourceLocator, collision),
        )
        assertFalse(rejected.accepted)
        assertTrue(rejected.issues.any { it.code == "JVW-ENTITY-INVERSE-NAME-COLLISION" })

        lateinit var isolatedRoot: com.intellij.openapi.vfs.VirtualFile
        WriteAction.run<RuntimeException> {
            isolatedRoot = requireNotNull(VfsUtil.createDirectoryIfMissing(root, "isolated-target"))
            val isolatedModule = ModuleManager.getInstance(project).newModule(
                "${isolatedRoot.path}/isolated-target.iml",
                ModuleType.EMPTY.id,
            )
            val isolatedModel = ModuleRootManager.getInstance(isolatedModule).modifiableModel
            isolatedModel.addContentEntry(isolatedRoot)
            isolatedModel.commit()
            write(
                isolatedRoot,
                "src/main/java/com/acme/isolated/IsolatedTarget.java",
                """
                package com.acme.isolated;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "ISOLATED_TARGET")
                public class IsolatedTarget {
                    @Id
                    private UUID id;
                }
                """.trimIndent(),
            )
        }
        val crossModuleWorkspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val isolatedTarget = crossModuleWorkspace.entities.single { it.className == "IsolatedTarget" }
        val crossModuleModel = sourceModel.copy(
            attributes = mutableListOf(
                AttributeModel(
                    name = "isolatedTarget",
                    type = AttributeType.ASSOCIATION,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = isolatedTarget.qualifiedName,
                        relatedTableName = isolatedTarget.tableName,
                        relatedIdColumnName = isolatedTarget.idColumnName,
                        relatedIdType = isolatedTarget.idType,
                        joinColumnName = "ISOLATED_TARGET_ID",
                        cascade = mutableListOf(),
                        fetch = FetchType.LAZY,
                        collectionType = AssociationCollectionType.LIST,
                        generateInverse = true,
                        inverseAttributeName = "assignments",
                    ),
                ),
            ),
        )
        val crossModuleRejected = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(javaSource.sourceLocator, crossModuleModel),
        )
        assertFalse(crossModuleRejected.accepted)
        assertTrue(
            crossModuleRejected.issues.any { it.code == "JVW-ENTITY-INVERSE-MODULE-CYCLE" },
            crossModuleRejected.issues.toString(),
        )
    }

    fun testOneToOneOwnershipTransferMovesForeignKeyDataAndBothSourcesAtomically() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/Account.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "ACCOUNT")
                public class Account {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;

                    @OneToOne(mappedBy = "account", fetch = FetchType.LAZY)
                    private AccountProfile profile;

                    public String manualAccountCode() { return "account"; }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/AccountProfile.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "ACCOUNT_PROFILE")
                open class AccountProfile {
                    @Id
                    @Column(name = "ID", nullable = false)
                    var id: UUID? = null

                    @OneToOne(fetch = FetchType.LAZY, optional = false)
                    @JoinColumn(name = "ACCOUNT_ID", nullable = false, unique = true)
                    var account: Account? = null

                    fun manualProfileCode(): String = "profile"
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinContract.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "KOTLIN_CONTRACT")
                open class KotlinContract {
                    @Id
                    @Column(name = "ID", nullable = false)
                    var id: UUID? = null

                    @OneToOne(mappedBy = "contract", fetch = FetchType.LAZY)
                    var owner: ContractOwner? = null

                    fun manualContractCode(): String = "contract"
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/ContractOwner.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "CONTRACT_OWNER")
                public class ContractOwner {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;

                    @OneToOne(fetch = FetchType.LAZY)
                    @JoinColumn(name = "CONTRACT_ID", unique = true)
                    private KotlinContract contract;

                    public String manualOwnerCode() { return "owner"; }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog/015-account-profile.xml",
                """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <changeSet id="account-profile" author="team">
                        <createTable tableName="ACCOUNT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                        </createTable>
                        <createTable tableName="ACCOUNT_PROFILE">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="ACCOUNT_ID" type="UUID">
                                <constraints nullable="false"/>
                            </column>
                        </createTable>
                        <addUniqueConstraint
                            tableName="ACCOUNT_PROFILE"
                            constraintName="UQ_ACCOUNT_PROFILE_ACCOUNT"
                            columnNames="ACCOUNT_ID"/>
                        <addForeignKeyConstraint
                            constraintName="FK_ACCOUNT_PROFILE_ACCOUNT"
                            baseTableName="ACCOUNT_PROFILE"
                            baseColumnNames="ACCOUNT_ID"
                            referencedTableName="ACCOUNT"
                            referencedColumnNames="ID"
                            onDelete="CASCADE"/>
                        <createTable tableName="KOTLIN_CONTRACT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                        </createTable>
                        <createTable tableName="CONTRACT_OWNER">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="CONTRACT_ID" type="UUID"/>
                        </createTable>
                        <createIndex
                            tableName="CONTRACT_OWNER"
                            indexName="UX_CONTRACT_OWNER_CONTRACT"
                            unique="true">
                            <column name="CONTRACT_ID"/>
                        </createIndex>
                        <addForeignKeyConstraint
                            constraintName="FK_CONTRACT_OWNER_CONTRACT"
                            baseTableName="CONTRACT_OWNER"
                            baseColumnNames="CONTRACT_ID"
                            referencedTableName="KOTLIN_CONTRACT"
                            referencedColumnNames="ID"/>
                    </changeSet>
                </databaseChangeLog>
                """.trimIndent(),
            )
        }

        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val account = workspace.entities.single { it.className == "Account" }
        val profile = workspace.entities.single { it.className == "AccountProfile" }
        val current = account.attributes.single { it.name == "profile" }
        val relation = requireNotNull(current.associationDetails)
        val store = workspace.stores.single()
        val requested = EntityModel(
            className = account.className,
            packageName = account.qualifiedName.substringBeforeLast('.'),
            sourceLanguage = EntitySourceLanguage.JAVA,
            tableName = account.tableName,
            dataStore = account.storeName,
            generationTarget = EntityGenerationTarget(account.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = current.name,
                    type = AttributeType.ASSOCIATION,
                    columnName = current.columnName,
                    mandatory = !current.nullable,
                    unique = current.unique,
                    association = AssociationConfig(
                        associationType = relation.associationType,
                        relatedEntity = relation.relatedEntity,
                        relatedTableName = relation.relatedTableName,
                        relatedIdColumnName = relation.relatedIdColumnName,
                        relatedIdType = relation.relatedIdType,
                        mappedBy = relation.mappedBy,
                        joinColumnName = relation.joinColumnName,
                        cascade = relation.cascade.toMutableList(),
                        fetch = relation.fetch,
                        collectionType = relation.collectionType,
                        crossDataStore = relation.crossDataStore,
                        orphanRemoval = relation.orphanRemoval,
                        onDelete = relation.onDelete,
                        ownershipTransfer = AssociationOwnershipTransfer.REQUEST,
                        ownershipJoinColumnName = "PROFILE_ID",
                    ),
                ),
            ),
        )
        val service = ExistingEntityChangeService.getInstance(project)
        val preview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(account.sourceLocator, requested),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(3, preview.files.size)
        val java = preview.files.single { it.relativePath.endsWith("/Account.java") }.resultContent
        val kotlin = preview.files.single { it.relativePath.endsWith("/AccountProfile.kt") }.resultContent
        val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
        assertTrue(java.contains("@OneToOne(fetch = FetchType.LAZY)"))
        assertFalse(java.contains("mappedBy = \"account\""))
        assertTrue(java.contains("@JoinColumn(name = \"PROFILE_ID\", unique = true)"))
        assertTrue(java.contains("manualAccountCode"))
        assertTrue(kotlin.contains("@OneToOne("))
        assertTrue(kotlin.contains("mappedBy = \"profile\""))
        assertTrue(kotlin.contains("fetch = FetchType.LAZY"))
        assertFalse(kotlin.contains("optional = false"))
        assertFalse(kotlin.contains("@JoinColumn"))
        assertTrue(kotlin.contains("manualProfileCode"))
        assertTrue(migration.contains("""<addColumn tableName="ACCOUNT">"""))
        assertTrue(migration.contains("""name="PROFILE_ID" type="UUID""""))
        assertTrue(migration.contains("UPDATE ACCOUNT SET PROFILE_ID = ("))
        assertTrue(migration.contains("UPDATE ACCOUNT_PROFILE SET ACCOUNT_ID = ("))
        assertTrue(migration.contains("""constraintName="UQ_ACCOUNT_PROFILE_ACCOUNT""""))
        assertTrue(migration.contains("""constraintName="FK_ACCOUNT_PROFILE_ACCOUNT""""))
        assertTrue(migration.contains("""baseTableName="ACCOUNT" baseColumnNames="PROFILE_ID""""))
        assertTrue(migration.contains("""referencedTableName="ACCOUNT_PROFILE""""))
        assertTrue(migration.contains("""<dropColumn tableName="ACCOUNT_PROFILE" columnName="ACCOUNT_ID""""))
        assertTrue(migration.contains("""<addNotNullConstraint tableName="ACCOUNT_PROFILE" columnName="ACCOUNT_ID""""))
        assertTrue(migration.contains("<rollback>"))
        assertTrue(preview.files.none { it.relativePath.endsWith("LoanApp.java") })
        assertEquals(
            profile.qualifiedName,
            relation.relatedEntity,
        )

        val kotlinContract = workspace.entities.single { it.className == "KotlinContract" }
        val contractOwner = workspace.entities.single { it.className == "ContractOwner" }
        val currentOwner = kotlinContract.attributes.single { it.name == "owner" }
        val ownerRelation = requireNotNull(currentOwner.associationDetails)
        val kotlinRequest = EntityModel(
            className = kotlinContract.className,
            packageName = kotlinContract.qualifiedName.substringBeforeLast('.'),
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            tableName = kotlinContract.tableName,
            dataStore = kotlinContract.storeName,
            generationTarget = EntityGenerationTarget(kotlinContract.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = currentOwner.name,
                    type = AttributeType.ASSOCIATION,
                    columnName = currentOwner.columnName,
                    mandatory = !currentOwner.nullable,
                    unique = currentOwner.unique,
                    association = AssociationConfig(
                        associationType = ownerRelation.associationType,
                        relatedEntity = ownerRelation.relatedEntity,
                        relatedTableName = ownerRelation.relatedTableName,
                        relatedIdColumnName = ownerRelation.relatedIdColumnName,
                        relatedIdType = ownerRelation.relatedIdType,
                        mappedBy = ownerRelation.mappedBy,
                        joinColumnName = ownerRelation.joinColumnName,
                        cascade = ownerRelation.cascade.toMutableList(),
                        fetch = ownerRelation.fetch,
                        collectionType = ownerRelation.collectionType,
                        crossDataStore = ownerRelation.crossDataStore,
                        orphanRemoval = ownerRelation.orphanRemoval,
                        onDelete = ownerRelation.onDelete,
                        ownershipTransfer = AssociationOwnershipTransfer.REQUEST,
                        ownershipJoinColumnName = "OWNER_ID",
                    ),
                ),
            ),
        )
        val kotlinPreview = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(kotlinContract.sourceLocator, kotlinRequest),
        )
        assertTrue(
            kotlinPreview.accepted,
            kotlinPreview.issues.joinToString { "${it.code}: ${it.message}" },
        )
        assertEquals(3, kotlinPreview.files.size)
        val kotlinReceiver = kotlinPreview.files.single {
            it.relativePath.endsWith("/KotlinContract.kt")
        }.resultContent
        val javaReleaser = kotlinPreview.files.single {
            it.relativePath.endsWith("/ContractOwner.java")
        }.resultContent
        val indexMigration = kotlinPreview.files.single {
            it.relativePath.endsWith(".xml")
        }.resultContent
        assertTrue(kotlinReceiver.contains("@JoinColumn(name = \"OWNER_ID\", unique = true)"))
        assertFalse(kotlinReceiver.contains("mappedBy = \"contract\""))
        assertTrue(kotlinReceiver.contains("manualContractCode"))
        assertTrue(javaReleaser.contains("mappedBy = \"owner\""))
        assertFalse(javaReleaser.contains("@JoinColumn"))
        assertTrue(javaReleaser.contains("manualOwnerCode"))
        assertTrue(indexMigration.contains("""indexName="UX_CONTRACT_OWNER_CONTRACT""""))
        assertTrue(indexMigration.contains("""<dropIndex tableName="CONTRACT_OWNER""""))
        assertTrue(indexMigration.contains("""<createIndex tableName="CONTRACT_OWNER""""))
        assertTrue(indexMigration.contains("""unique="true""""))
        assertTrue(indexMigration.contains("UPDATE KOTLIN_CONTRACT SET OWNER_ID = ("))
        assertTrue(indexMigration.contains("UPDATE CONTRACT_OWNER SET CONTRACT_ID = ("))
        assertEquals(
            contractOwner.qualifiedName,
            ownerRelation.relatedEntity,
        )

        val collision = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                account.sourceLocator,
                requested.copy(
                    attributes = requested.attributes.map { attribute ->
                        attribute.copy(
                            association = attribute.association?.copy(
                                ownershipJoinColumnName = "ID",
                            ),
                        )
                    }.toMutableList(),
                ),
            ),
        )
        assertFalse(collision.accepted)
        assertTrue(
            collision.issues.any { it.code == "JVW-ENTITY-OWNERSHIP-TRANSFER-COLUMN-COLLISION" },
            collision.issues.toString(),
        )

        val prepared = service.prepareAttributeAdditions(
            ExistingEntityAttributeAdditionApplyRequest(
                change = ExistingEntityAttributeAdditionRequest(account.sourceLocator, requested),
                expectedPlanDigest = requireNotNull(preview.planDigest),
            ),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(3, applied.filesChanged.size)
        val refreshed = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val physical = refreshed.physicalSchemas.single { it.storeId == store.id }
        assertTrue(physical.complete)
        val accountTable = physical.tables.single { it.name == "ACCOUNT" }
        val profileTable = physical.tables.single { it.name == "ACCOUNT_PROFILE" }
        assertTrue(accountTable.columns.any { it.name == "PROFILE_ID" && it.unique })
        assertTrue(accountTable.foreignKeys.any {
            it.baseColumnNames == "PROFILE_ID" &&
                it.referencedTableName == "ACCOUNT_PROFILE" &&
                it.referencedColumnNames == "ID"
        })
        assertFalse(profileTable.columns.any { it.name == "ACCOUNT_ID" })
        assertFalse(profileTable.foreignKeys.any { it.constraintName == "FK_ACCOUNT_PROFILE_ACCOUNT" })
        assertFalse(profileTable.uniqueConstraints.any { it.name == "UQ_ACCOUNT_PROFILE_ACCOUNT" })
        assertFalse(refreshed.findings.any { it.code == "SCHEMA_RAW_SQL_NOT_SCOPED" })
        val refreshedAccount = refreshed.entities.single { it.className == "Account" }
        val refreshedProfile = refreshed.entities.single { it.className == "AccountProfile" }
        val refreshedReceiver = requireNotNull(
            refreshedAccount.attributes.single { it.name == "profile" }.associationDetails,
        )
        val refreshedReleaser = assertNotNull(
            refreshedProfile.attributes.single { it.name == "account" }.associationDetails,
            kotlin,
        )
        assertEquals(null, refreshedReceiver.mappedBy)
        assertEquals("PROFILE_ID", refreshedReceiver.joinColumnName)
        assertEquals("profile", refreshedReleaser.mappedBy)
        assertEquals(null, refreshedReleaser.joinColumnName)
    }

    fun testOwningRelationshipsChangeNullabilityOnlyWithExactPhysicalProof() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/MandatoryDepartment.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "MANDATORY_DEPARTMENT")
                public class MandatoryDepartment {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/OptionalAssignment.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "OPTIONAL_ASSIGNMENT")
                public class OptionalAssignment {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;

                    @ManyToOne(fetch = FetchType.LAZY)
                    @JoinColumn(
                        name = "DEPARTMENT_ID",
                        referencedColumnName = "ID",
                        foreignKey = @ForeignKey(name = "FK_OPTIONAL_ASSIGNMENT_DEPARTMENT")
                    )
                    private MandatoryDepartment department;

                    public String manualAssignmentCode() { return "assignment"; }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/OptionalApproval.kt",
                """
                package com.acme.entity
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.*
                import java.util.UUID
                @JmixEntity
                @Entity
                @Table(name = "OPTIONAL_APPROVAL")
                open class OptionalApproval {
                    @Id
                    @Column(name = "ID", nullable = false)
                    var id: UUID? = null

                    @OneToOne(fetch = FetchType.LAZY)
                    @JoinColumn(
                        name = "DEPARTMENT_ID",
                        referencedColumnName = "ID",
                        unique = true,
                        foreignKey = ForeignKey(name = "FK_OPTIONAL_APPROVAL_DEPARTMENT"),
                    )
                    var department: MandatoryDepartment? = null

                    fun manualApprovalCode(): String = "approval"
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/RequiredAssignment.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "REQUIRED_ASSIGNMENT")
                public class RequiredAssignment {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;

                    @ManyToOne(optional = false)
                    @JoinColumn(name = "DEPARTMENT_ID", nullable = false)
                    private MandatoryDepartment department;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/UnprovenOptionalAssignment.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                import java.util.UUID;
                @JmixEntity
                @Entity
                @Table(name = "UNPROVEN_OPTIONAL_ASSIGNMENT")
                public class UnprovenOptionalAssignment {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;

                    @ManyToOne
                    @JoinColumn(name = "DEPARTMENT_ID")
                    private MandatoryDepartment department;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog/016-mandatory-relationships.xml",
                """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <changeSet id="mandatory-relationships" author="team">
                        <createTable tableName="MANDATORY_DEPARTMENT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                        </createTable>
                        <createTable tableName="OPTIONAL_ASSIGNMENT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="DEPARTMENT_ID" type="UUID"/>
                        </createTable>
                        <addForeignKeyConstraint
                            constraintName="FK_OPTIONAL_ASSIGNMENT_DEPARTMENT"
                            baseTableName="OPTIONAL_ASSIGNMENT"
                            baseColumnNames="DEPARTMENT_ID"
                            referencedTableName="MANDATORY_DEPARTMENT"
                            referencedColumnNames="ID"/>
                        <createTable tableName="OPTIONAL_APPROVAL">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="DEPARTMENT_ID" type="UUID"/>
                        </createTable>
                        <addUniqueConstraint
                            tableName="OPTIONAL_APPROVAL"
                            constraintName="UQ_OPTIONAL_APPROVAL_DEPARTMENT"
                            columnNames="DEPARTMENT_ID"/>
                        <addForeignKeyConstraint
                            constraintName="FK_OPTIONAL_APPROVAL_DEPARTMENT"
                            baseTableName="OPTIONAL_APPROVAL"
                            baseColumnNames="DEPARTMENT_ID"
                            referencedTableName="MANDATORY_DEPARTMENT"
                            referencedColumnNames="ID"/>
                        <createTable tableName="REQUIRED_ASSIGNMENT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="DEPARTMENT_ID" type="UUID">
                                <constraints nullable="false"/>
                            </column>
                        </createTable>
                        <addForeignKeyConstraint
                            constraintName="FK_REQUIRED_ASSIGNMENT_DEPARTMENT"
                            baseTableName="REQUIRED_ASSIGNMENT"
                            baseColumnNames="DEPARTMENT_ID"
                            referencedTableName="MANDATORY_DEPARTMENT"
                            referencedColumnNames="ID"/>
                        <createTable tableName="UNPROVEN_OPTIONAL_ASSIGNMENT">
                            <column name="ID" type="UUID">
                                <constraints nullable="false" primaryKey="true"/>
                            </column>
                            <column name="DEPARTMENT_ID" type="UUID"/>
                        </createTable>
                    </changeSet>
                </databaseChangeLog>
                """.trimIndent(),
            )
        }

        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val store = workspace.stores.single()
        val service = ExistingEntityChangeService.getInstance(project)

        fun model(
            snapshot: SchemaEntitySnapshot,
            mandatory: Boolean,
            associationType: AssociationType? = null,
            unique: Boolean? = null,
            joinColumnName: String? = null,
        ): EntityModel {
            val current = snapshot.attributes.single { it.name == "department" }
            val relation = requireNotNull(current.associationDetails)
            return EntityModel(
                className = snapshot.className,
                packageName = snapshot.qualifiedName.substringBeforeLast('.'),
                sourceLanguage = if (snapshot.sourceLocator.relativePath.endsWith(".kt")) {
                    EntitySourceLanguage.KOTLIN
                } else {
                    EntitySourceLanguage.JAVA
                },
                tableName = snapshot.tableName,
                dataStore = snapshot.storeName,
                generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
                attributes = mutableListOf(
                    AttributeModel(
                        name = current.name,
                        type = AttributeType.ASSOCIATION,
                        columnName = current.columnName,
                        mandatory = mandatory,
                        unique = unique ?: current.unique,
                        association = AssociationConfig(
                            associationType = associationType ?: relation.associationType,
                            relatedEntity = relation.relatedEntity,
                            relatedTableName = relation.relatedTableName,
                            relatedIdColumnName = relation.relatedIdColumnName,
                            relatedIdType = relation.relatedIdType,
                            localIdAttributeName = relation.localIdAttributeName,
                            mappedBy = relation.mappedBy,
                            joinColumnName = joinColumnName ?: relation.joinColumnName,
                            joinTable = relation.joinTable,
                            cascade = relation.cascade.toMutableList(),
                            fetch = relation.fetch,
                            collectionType = relation.collectionType,
                            crossDataStore = relation.crossDataStore,
                            orphanRemoval = relation.orphanRemoval,
                            onDelete = relation.onDelete,
                        ),
                    ),
                ),
            )
        }

        listOf("OptionalAssignment", "OptionalApproval").forEach { className ->
            val snapshot = workspace.entities.single { it.className == className }
            val preview = service.previewAttributeAdditions(
                ExistingEntityAttributeAdditionRequest(
                    snapshot.sourceLocator,
                    model(snapshot, mandatory = true),
                ),
            )
            assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
            assertEquals(2, preview.files.size)
            val source = preview.files.single {
                it.relativePath.endsWith(if (className == "OptionalApproval") ".kt" else ".java")
            }.resultContent
            assertTrue(source.contains("optional = false"))
            assertTrue(source.contains("nullable = false"))
            assertTrue(source.contains("referencedColumnName = \"ID\""))
            assertTrue(source.contains("manual"))
            if (className == "OptionalApproval") {
                assertTrue(source.contains("unique = true"))
                assertTrue(source.contains("FK_OPTIONAL_APPROVAL_DEPARTMENT"))
            } else {
                assertTrue(source.contains("FK_OPTIONAL_ASSIGNMENT_DEPARTMENT"))
            }
            val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
            assertTrue(migration.contains("<preConditions onFail=\"HALT\" onError=\"HALT\">"))
            assertTrue(migration.contains("columnExists"))
            assertTrue(migration.contains("WHERE DEPARTMENT_ID IS NULL"))
            assertTrue(migration.contains("expectedResult=\"0\""))
            assertTrue(migration.contains("<addNotNullConstraint"))
            assertTrue(migration.contains("<rollback>"))
            assertTrue(migration.contains("<dropNotNullConstraint"))
        }

        listOf(
            "OptionalAssignment" to "PRIMARY_DEPARTMENT_ID",
            "OptionalApproval" to "APPROVER_DEPARTMENT_ID",
        ).forEach { (className, renamedColumn) ->
            val snapshot = workspace.entities.single { it.className == className }
            val preview = service.previewAttributeAdditions(
                ExistingEntityAttributeAdditionRequest(
                    snapshot.sourceLocator,
                    model(
                        snapshot = snapshot,
                        mandatory = true,
                        joinColumnName = renamedColumn,
                    ),
                ),
            )
            assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
            val source = preview.files.single {
                it.relativePath.endsWith(if (className == "OptionalApproval") ".kt" else ".java")
            }.resultContent
            assertTrue(source.contains("""name = "$renamedColumn""""))
            assertTrue(source.contains("optional = false"))
            assertTrue(source.contains("nullable = false"))
            assertTrue(source.contains("manual"))
            val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
            assertTrue(migration.contains("""<columnExists tableName="${snapshot.tableName}" columnName="DEPARTMENT_ID""""))
            assertTrue(
                Regex(
                    """<not>\s*<columnExists tableName="${snapshot.tableName}" columnName="$renamedColumn"/>\s*</not>""",
                ).containsMatchIn(migration),
            )
            assertTrue(migration.contains("WHERE DEPARTMENT_ID IS NULL"))
            assertFalse(migration.contains("WHERE $renamedColumn IS NULL"))
            assertTrue(
                migration.contains(
                    """<renameColumn tableName="${snapshot.tableName}" oldColumnName="DEPARTMENT_ID" newColumnName="$renamedColumn"""",
                ),
            )
            assertTrue(
                migration.contains(
                    """<addNotNullConstraint tableName="${snapshot.tableName}" columnName="$renamedColumn"""",
                ),
            )
            assertTrue(
                migration.contains(
                    """<dropNotNullConstraint tableName="${snapshot.tableName}" columnName="$renamedColumn"""",
                ),
            )
            val rollbackStart = migration.indexOf("<rollback>")
            assertTrue(rollbackStart >= 0)
            val rollback = migration.substring(rollbackStart)
            assertTrue(
                rollback.indexOf("<dropNotNullConstraint") <
                    rollback.indexOf("""oldColumnName="$renamedColumn" newColumnName="DEPARTMENT_ID""""),
            )
        }

        val destinationCollisionSource = workspace.entities.single {
            it.className == "OptionalAssignment"
        }
        val destinationCollision = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                destinationCollisionSource.sourceLocator,
                model(
                    snapshot = destinationCollisionSource,
                    mandatory = true,
                    joinColumnName = "ID",
                ),
            ),
        )
        assertFalse(destinationCollision.accepted)
        assertTrue(
            destinationCollision.issues.any {
                it.code == "JVW-ENTITY-RELATIONSHIP-NULLABILITY-DESTINATION-COLLISION"
            },
            destinationCollision.issues.toString(),
        )

        val required = workspace.entities.single { it.className == "RequiredAssignment" }
        val loosening = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                required.sourceLocator,
                model(required, mandatory = false),
            ),
        )
        assertTrue(
            loosening.accepted,
            loosening.issues.joinToString { "${it.code}: ${it.message}" },
        )
        val looseningSource = loosening.files.single {
            it.relativePath.endsWith("/RequiredAssignment.java")
        }.resultContent
        assertFalse(looseningSource.contains("optional = false"))
        assertFalse(looseningSource.contains("@JoinColumn(name = \"DEPARTMENT_ID\", nullable = false"))
        assertTrue(looseningSource.contains("@JoinColumn(name = \"DEPARTMENT_ID\")"))
        val looseningMigration = loosening.files.single { it.relativePath.endsWith(".xml") }.resultContent
        assertTrue(looseningMigration.contains("<dropNotNullConstraint"))
        assertTrue(looseningMigration.contains("<rollback>"))
        assertTrue(looseningMigration.contains("<addNotNullConstraint"))

        val unproven = workspace.entities.single { it.className == "UnprovenOptionalAssignment" }
        val missingForeignKey = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                unproven.sourceLocator,
                model(unproven, mandatory = true),
            ),
        )
        assertFalse(missingForeignKey.accepted)
        assertTrue(
            missingForeignKey.issues.any {
                it.code == "JVW-ENTITY-RELATIONSHIP-NULLABILITY-FOREIGN-KEY-MISSING"
            },
            missingForeignKey.issues.toString(),
        )

        val optional = workspace.entities.single { it.className == "OptionalAssignment" }
        val combinedCardinalityChange = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                optional.sourceLocator,
                model(
                    optional,
                    mandatory = true,
                    associationType = AssociationType.ONE_TO_ONE,
                    unique = true,
                ),
            ),
        )
        assertFalse(combinedCardinalityChange.accepted)
        assertTrue(
            combinedCardinalityChange.issues.any {
                it.code == "JVW-ENTITY-RELATIONSHIP-SHAPE-REQUIRES-IMPACT"
            },
            combinedCardinalityChange.issues.toString(),
        )
    }

    fun testIncludeAllStoreEntityCoverageAndSourceSafeMigrationDestination() {
        createFixture(includeAll = true)
        val service = SchemaWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)

        assertTrue(workspace.accepted)
        assertTrue(
            workspace.stores.isNotEmpty(),
            "No stores indexed; project=${project.basePath}, modules=${ModuleManager.getInstance(project).modules.map { it.name }}",
        )
        val store = workspace.stores.single()
        assertEquals(SchemaIncludeMode.INCLUDE_ALL, store.includeMode)
        assertTrue(store.generatedDirectory!!.endsWith("com/acme/liquibase/changelog"))
        val entity = workspace.entities.single { it.className == "LoanApp" }
        assertEquals("LOAN_APP", entity.tableName)
        assertEquals(SchemaMigrationCoverage.COVERED, entity.migrationCoverage)
        assertTrue(workspace.findings.any { it.code == "SCHEMA_BUSINESS_IDENTIFIER_NOT_UNIQUE" })
        val physicalStore = workspace.physicalSchemas.single()
        assertTrue(physicalStore.complete)
        assertTrue(physicalStore.changelogPaths.any { it.endsWith("/010-init.xml") })
        val physicalTable = physicalStore.tables.single { it.name == "LOAN_APP" }
        assertEquals(listOf("ID"), physicalTable.columns.map { it.name })
        assertFalse(workspace.drifts.any { it.kind == SchemaDriftKind.TABLE_MISSING })
        val missingColumns = workspace.drifts
            .filter { it.kind == SchemaDriftKind.COLUMN_MISSING }
            .associateBy { it.columnName }
        assertEquals(setOf("APPLICATION_NO", "LOAN_AMOUNT"), missingColumns.keys)
        assertEquals("VARCHAR(255)", missingColumns.getValue("APPLICATION_NO").suggestion?.columnType)
        assertEquals(false, missingColumns.getValue("APPLICATION_NO").suggestion?.nullable)
        assertEquals("DECIMAL(19,2)", missingColumns.getValue("LOAN_AMOUNT").suggestion?.columnType)
        assertTrue(workspace.drifts.all { it.confidence == SchemaDriftConfidence.HIGH })

        val request = SchemaMigrationChangeRequest(store.id, addStatusMigration())
        val preview = service.previewMigration(request)
        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        assertNotNull(preview.planDigest)
        assertEquals(1, preview.files.size)
        assertEquals(org.jmixworkbench.discovery.change.WorkspaceFileChangeMode.CREATE, preview.files.single().mode)
        assertTrue(preview.files.single().relativePath.contains("/liquibase/changelog/"))
    }

    fun testExplicitIncludeRootIsEditedAndDuplicateChangesetIsRejected() {
        createFixture(includeAll = false)
        val service = SchemaWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        assertTrue(
            workspace.stores.isNotEmpty(),
            "No stores indexed; project=${project.basePath}, modules=${ModuleManager.getInstance(project).modules.map { it.name }}",
        )
        val store = workspace.stores.single()
        assertEquals(SchemaIncludeMode.EXPLICIT, store.includeMode)

        val preview = service.previewMigration(SchemaMigrationChangeRequest(store.id, addStatusMigration()))
        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        assertEquals(2, preview.files.size)
        val root = preview.files.single { it.relativePath.endsWith("liquibase/changelog.xml") }
        assertEquals(org.jmixworkbench.discovery.change.WorkspaceFileChangeMode.MODIFY, root.mode)
        assertTrue(root.resultContent.contains("<include file=\"com/acme/liquibase/changelog/"))

        val duplicate = addStatusMigration().copy(
            changes = mutableListOf(
                ChangeSetModel(
                    id = "existing",
                    author = "team",
                    changes = mutableListOf(DbChange.AddColumn("LOAN_APP", mutableListOf(ColumnDef("X", "INT")))),
                ),
            ),
        )
        val rejected = service.previewMigration(SchemaMigrationChangeRequest(store.id, duplicate))
        assertFalse(rejected.accepted)
        assertTrue(rejected.issues.any { it.code == "JVW-SCHEMA-CHANGESET-DUPLICATE" })
    }

    fun testEntityGenerationUsesSelectedModuleStoreAndRevisionBoundPreview() {
        createFixture(includeAll = true)
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val store = workspace.stores.single()
        val entity = EntityModel(
            className = "RepaymentSchedule",
            packageName = "com.acme.entity",
            dataStore = store.name,
            generationTarget = EntityGenerationTarget(
                moduleId = store.moduleId,
                storeId = store.id,
            ),
            dataRepository = DataRepositoryConfig(enabled = true),
        )
        val preview = CodeGenerationService.getInstance(project).previewEntityGeneration(
            entity,
            ProjectConfig(
                projectRoot = requireNotNull(project.basePath),
                basePackage = "com.acme",
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        assertNotNull(preview.planDigest)
        assertTrue(
            preview.files.any {
                it.relativePath.endsWith("src/main/java/com/acme/entity/RepaymentSchedule.java")
            },
        )
        assertTrue(
            preview.files.any {
                it.relativePath.contains("/liquibase/changelog/") &&
                    it.relativePath.endsWith("-create-repayment_schedule.xml")
            },
        )
        assertTrue(
            preview.files.any {
                it.relativePath.endsWith("src/main/resources/com/acme/entity/messages.properties")
            },
        )
        assertTrue(
            preview.files.any {
                it.relativePath.endsWith("src/main/java/com/acme/entity/RepaymentScheduleRepository.java")
            },
        )
        val repositoryConfiguration = preview.files.single {
            it.relativePath.endsWith("src/main/java/com/acme/JmixDataRepositoryConfiguration.java")
        }
        assertTrue(repositoryConfiguration.resultContent.contains("@EnableJmixDataRepositories"))
        assertTrue(repositoryConfiguration.resultContent.contains("basePackages = \"com.acme.entity\""))

        val crudPreview = CodeGenerationService.getInstance(project).previewCrudGeneration(
            entity,
            ProjectConfig(
                projectRoot = requireNotNull(project.basePath),
                basePackage = "com.acme",
            ),
            CrudOrchestrator.CrudOptions(generateDataRepository = true),
        )
        assertTrue(crudPreview.accepted, crudPreview.issues.joinToString { it.message })
        assertEquals(12, crudPreview.files.size)
        assertFalse(crudPreview.files.any { "/entity/entity/" in it.relativePath })
        assertTrue(
            crudPreview.files.any {
                it.relativePath.endsWith("src/main/java/com/acme/entity/RepaymentSchedule.java")
            },
        )
        assertTrue(
            crudPreview.files.any {
                it.relativePath.endsWith("src/main/java/com/acme/view/RepaymentScheduleListView.java")
            },
        )
        assertTrue(
            crudPreview.files.any {
                it.relativePath.contains("/liquibase/changelog/") &&
                    it.relativePath.endsWith("-create-repayment_schedule.xml")
            },
        )
    }

    fun testExistingEntityViewGenerationRequiresExactSourceAndNeverRecreatesEntityOrTable() {
        createFixture(includeAll = true)
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val snapshot = workspace.entities.single { it.className == "LoanApp" }
        val store = workspace.stores.single()
        val entity = EntityModel(
            className = snapshot.className,
            packageName = snapshot.qualifiedName.substringBeforeLast('.'),
            entityName = snapshot.entityName,
            tableName = snapshot.tableName,
            tableSchema = snapshot.tableSchema,
            tableCatalog = snapshot.tableCatalog,
            dataStore = snapshot.storeName,
            entityType = snapshot.entityType,
            id = IdConfig(
                type = snapshot.idType,
                columnName = snapshot.idColumnName,
            ),
            traits = snapshot.traits.toMutableList(),
            databaseView = snapshot.databaseView,
            generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
            attributes = snapshot.attributes.map { attribute ->
                AttributeModel(
                    name = attribute.name,
                    type = when {
                        attribute.javaType.endsWith("BigDecimal") -> AttributeType.BIG_DECIMAL
                        attribute.javaType.endsWith("UUID") -> AttributeType.UUID
                        else -> AttributeType.STRING
                    },
                    columnName = attribute.columnName,
                    mandatory = !attribute.nullable,
                    unique = attribute.unique,
                    length = attribute.length,
                    precision = attribute.precision,
                    scale = attribute.scale,
                    transientFlag = !attribute.persistent,
                )
            }.toMutableList(),
        )
        val config = ProjectConfig(
            projectRoot = requireNotNull(project.basePath),
            basePackage = "com.acme",
        )
        val options = CrudOrchestrator.CrudOptions(
            generateEntity = false,
            existingEntitySource = snapshot.sourceLocator,
            generateMigration = true,
            generateSecurityRole = false,
        )

        val preview = CodeGenerationService.getInstance(project)
            .previewCrudGeneration(entity, config, options)

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        assertTrue(preview.label.contains("existing LoanApp"))
        assertFalse(preview.files.any { it.relativePath.endsWith("/entity/LoanApp.java") })
        assertFalse(preview.files.any { it.relativePath.contains("/liquibase/changelog/") })
        assertFalse(preview.files.any { it.relativePath.endsWith("/security/LoanAppRole.java") })
        assertTrue(preview.files.any { it.relativePath.endsWith("/view/LoanAppListView.xml") })
        assertTrue(preview.files.any { it.relativePath.endsWith("/view/LoanAppDetailView.java") })

        val stale = CodeGenerationService.getInstance(project).previewCrudGeneration(
            entity,
            config,
            options.copy(
                existingEntitySource = snapshot.sourceLocator.copy(
                    revisionFingerprint = "stale-revision",
                ),
            ),
        )
        assertFalse(stale.accepted)
        assertTrue(
            stale.issues.any { it.message.contains("JVW-CRUD-EXISTING-SOURCE-STALE") },
            stale.issues.joinToString { it.message },
        )
    }

    fun testKotlinEntityGenerationTargetsKotlinSourceSetWithNativeRepositoryConfiguration() {
        createFixture(includeAll = true)
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val store = workspace.stores.single()
        val entity = EntityModel(
            className = "KotlinRepaymentSchedule",
            packageName = "com.acme.entity",
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            dataStore = store.name,
            generationTarget = EntityGenerationTarget(store.moduleId, store.id),
            dataRepository = DataRepositoryConfig(enabled = true),
        )

        val preview = CodeGenerationService.getInstance(project).previewEntityGeneration(
            entity,
            ProjectConfig(
                projectRoot = requireNotNull(project.basePath),
                basePackage = "com.acme",
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val source = preview.files.single {
            it.relativePath.endsWith(
                "src/main/kotlin/com/acme/entity/KotlinRepaymentSchedule.kt",
            )
        }
        assertTrue(source.resultContent.contains("open class KotlinRepaymentSchedule"))
        assertTrue(
            preview.files.any {
                it.relativePath.endsWith(
                    "src/main/kotlin/com/acme/entity/KotlinRepaymentScheduleRepository.kt",
                )
            },
        )
        val activation = preview.files.single {
            it.relativePath.endsWith(
                "src/main/kotlin/com/acme/JmixDataRepositoryConfiguration.kt",
            )
        }
        assertTrue(activation.resultContent.contains("basePackages = [\"com.acme.entity\"]"))
        assertFalse(preview.files.any { it.relativePath.endsWith(".java") })
    }

    fun testDatabaseEntityImportAtomicallyReplacesItsSourceControlledProfile() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val store = workspace.stores.single()
        val table = DatabaseTableSnapshot(
            catalog = "payroll",
            schema = "public",
            name = "ACCOUNT",
            type = "TABLE",
            remarks = "Accounts",
            primaryKeyColumns = listOf("ID"),
        )
        val entity = EntityModel(
            className = "Account",
            packageName = "com.acme.entity",
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            dataStore = store.name,
            generationTarget = EntityGenerationTarget(store.moduleId, store.id),
            tableName = table.name,
            tableSchema = table.schema,
            tableCatalog = table.catalog,
            ddlGeneration = DdlGenerationConfig(
                enabled = false,
                mode = DdlGenerationMode.DISABLED,
            ),
        )
        val oldRequest = databaseProfileRequest(store, "Old account mapping")
        val newRequest = databaseProfileRequest(store, "Reviewed account mapping")
        val plan = DatabaseEntityImportPlanResponse(
            accepted = true,
            ready = true,
            snapshotDigest = "c".repeat(64),
            storeId = store.id,
            database = DatabaseProductSnapshot(
                "PostgreSQL",
                "17",
                "PostgreSQL JDBC Driver",
                "42",
                "fingerprint",
            ),
            tables = listOf(
                DatabaseEntityImportTablePlan(
                    table,
                    selectedByUser = true,
                    requiredBy = emptyList(),
                    status = DatabaseEntityImportStatus.READY,
                    entityClassName = entity.className,
                    entityQualifiedName = entity.fullName,
                    compositeIdClassName = null,
                    generated = true,
                    issues = emptyList(),
                ),
            ),
            entities = listOf(entity),
            issues = emptyList(),
        )
        val oldProfile = requireNotNull(DatabaseEntityImportProfileService.fromPlan(oldRequest, plan))
        val newProfile = requireNotNull(DatabaseEntityImportProfileService.fromPlan(newRequest, plan))
        WriteAction.run<RuntimeException> {
            write(
                root,
                DatabaseEntityImportProfileService.path(oldProfile),
                DatabaseEntityImportProfileService.serialize(oldProfile),
            )
        }

        val preview = CodeGenerationService.getInstance(project).previewDatabaseEntityImport(
            listOf(entity),
            ProjectConfig(
                projectRoot = requireNotNull(project.basePath),
                basePackage = "com.acme",
            ),
            newProfile,
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val profileFile = preview.files.single {
            it.relativePath == ".jmix-workbench/database-imports/account-model.json"
        }
        assertEquals(
            org.jmixworkbench.discovery.change.WorkspaceFileChangeMode.MODIFY,
            profileFile.mode,
        )
        assertTrue(profileFile.originalContent?.contains("Old account mapping") == true)
        assertTrue(profileFile.resultContent.contains("Reviewed account mapping"))
        assertTrue(preview.files.any { it.relativePath.endsWith("/Account.kt") })
        assertFalse(preview.files.any { it.relativePath.contains("liquibase") })
    }

    fun testExistingEntityAttributeAdditionPreservesSourceAndAddsRollbackMigration() {
        createFixture(includeAll = true)
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val snapshot = workspace.entities.single { it.className == "LoanApp" }
        val store = workspace.stores.single()
        val entity = EntityModel(
            className = snapshot.className,
            packageName = snapshot.qualifiedName.substringBeforeLast('.'),
            tableName = snapshot.tableName,
            dataStore = snapshot.storeName,
            generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = "applicationNo",
                    type = AttributeType.STRING,
                    columnName = "APPLICATION_NO",
                    mandatory = true,
                ),
                AttributeModel(
                    name = "loanAmount",
                    type = AttributeType.BIG_DECIMAL,
                    columnName = "LOAN_AMOUNT",
                    mandatory = true,
                    precision = 19,
                    scale = 2,
                ),
                AttributeModel(
                    name = "status",
                    type = AttributeType.STRING,
                    columnName = "STATUS",
                    mandatory = true,
                    length = 32,
                ),
            ),
        )
        val request = ExistingEntityAttributeAdditionRequest(snapshot.sourceLocator, entity)
        val rename = EntityAttributeRefactorService.getInstance(project).prepareRename(
            EntityAttributeRenameRequest(
                sourceLocator = snapshot.sourceLocator,
                entityClassName = snapshot.className,
                attributeName = "applicationNo",
                newName = "businessApplicationNo",
            ),
        )
        assertTrue(rename.accepted, "${rename.code}: ${rename.message}")
        assertEquals("applicationNo", rename.element?.name)
        val collision = EntityAttributeRefactorService.getInstance(project).prepareRename(
            EntityAttributeRenameRequest(
                sourceLocator = snapshot.sourceLocator,
                entityClassName = snapshot.className,
                attributeName = "applicationNo",
                newName = "loanAmount",
            ),
        )
        assertFalse(collision.accepted)
        assertEquals("JVW-ENTITY-RENAME-COLLISION", collision.code)
        val preview = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(request)

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertNotNull(preview.planDigest)
        assertEquals(2, preview.files.size)
        val java = preview.files.single { it.relativePath.endsWith("LoanApp.java") }
        assertEquals(org.jmixworkbench.discovery.change.WorkspaceFileChangeMode.MODIFY, java.mode)
        assertTrue(java.resultContent.contains("public int calculateRisk()"))
        assertTrue(java.resultContent.contains("@Column(name = \"STATUS\", nullable = false, length = 32)"))
        assertTrue(java.resultContent.contains("protected String status;"))
        assertTrue(java.resultContent.contains("public String getStatus()"))
        val migration = preview.files.single { it.relativePath.endsWith(".xml") }
        assertTrue(migration.resultContent.contains("<addColumn tableName=\"LOAN_APP\">"))
        assertTrue(migration.resultContent.contains("<dropColumn tableName=\"LOAN_APP\" columnName=\"STATUS\""))

        val stale = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            request.copy(sourceLocator = snapshot.sourceLocator.copy(revisionFingerprint = "stale")),
        )
        assertFalse(stale.accepted)
        assertTrue(stale.issues.any { it.code == "JVW-ENTITY-SOURCE-STALE" })

        val ambiguousRemoval = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            request.copy(
                entity = entity.copy(
                    attributes = entity.attributes
                        .filterNot { it.name == "applicationNo" }
                        .toMutableList(),
                ),
            ),
        )
        assertFalse(ambiguousRemoval.accepted)
        assertTrue(
            ambiguousRemoval.issues.any { it.code == "JVW-ENTITY-REMOVAL-REQUIRES-IMPACT" },
        )

        val columnCollision = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            request.copy(
                entity = entity.copy(
                    attributes = entity.attributes.map {
                        if (it.name == "applicationNo") {
                            it.copy(columnName = "LOAN_AMOUNT")
                        } else {
                            it
                        }
                    }.toMutableList(),
                ),
            ),
        )
        assertFalse(columnCollision.accepted)
        assertTrue(
            columnCollision.issues.any { it.code == "JVW-ENTITY-COLUMN-RENAME-COLLISION" },
        )
    }

    fun testNonPersistentAndReusableHandwrittenJmixTypesRoundTripWithoutTableDdl() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/PayrollProjection.java",
                """
                package com.acme.entity;

                import io.jmix.core.metamodel.annotation.JmixEntity;

                @JmixEntity(name = "acme_PayrollProjection")
                public class PayrollProjection {
                    private String runNo;

                    public String manualLabel() {
                        return "Payroll " + runNo;
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/LoanTerms.kt",
                """
                package com.acme.entity

                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.Column
                import jakarta.persistence.Embeddable

                @JmixEntity
                @Embeddable
                open class LoanTerms {
                    @Column(name = "TERM_MONTHS", nullable = false)
                    var termMonths: Int? = null
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/AuditedRecord.java",
                """
                package com.acme.entity;

                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.Column;
                import jakarta.persistence.MappedSuperclass;

                @JmixEntity
                @MappedSuperclass
                public abstract class AuditedRecord {
                    @Column(name = "AUDIT_NOTE")
                    protected String auditNote;
                }
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val projection = workspace.entities.single { it.className == "PayrollProjection" }
        val terms = workspace.entities.single { it.className == "LoanTerms" }
        val mapped = workspace.entities.single { it.className == "AuditedRecord" }

        assertEquals(EntityType.DTO, projection.entityType)
        assertEquals(EntityType.EMBEDDABLE, terms.entityType)
        assertEquals(EntityType.MAPPED_SUPERCLASS, mapped.entityType)
        listOf(projection, terms, mapped).forEach {
            assertEquals("", it.tableName)
            assertEquals(SchemaDdlMode.DISABLED, it.ddlMode)
            assertEquals(SchemaMigrationCoverage.DISABLED, it.migrationCoverage)
            assertTrue(workspace.drifts.none { drift -> drift.entityArtifactId == it.artifactId })
        }
        assertFalse(projection.attributes.single { it.name == "runNo" }.persistent)
        assertTrue(terms.attributes.single { it.name == "termMonths" }.persistent)

        val store = workspace.stores.single()
        val desired = EntityModel(
            className = projection.className,
            packageName = projection.qualifiedName.substringBeforeLast('.'),
            entityType = EntityType.DTO,
            entityName = projection.entityName,
            tableName = "",
            dataStore = projection.storeName,
            generationTarget = EntityGenerationTarget(projection.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = "runNo",
                    type = AttributeType.STRING,
                    transientFlag = true,
                ),
                AttributeModel(
                    name = "netAmount",
                    type = AttributeType.BIG_DECIMAL,
                    transientFlag = true,
                ),
            ),
            ddlGeneration = DdlGenerationConfig(
                enabled = false,
                mode = DdlGenerationMode.DISABLED,
            ),
        )
        val preview = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(projection.sourceLocator, desired),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(1, preview.files.size)
        val java = preview.files.single().resultContent
        assertTrue(java.contains("public String manualLabel()"))
        assertTrue(java.contains("protected BigDecimal netAmount;"))
        assertFalse(java.contains("""@Column(name = "NET_AMOUNT""""))
    }

    fun testHandwrittenJavaAndKotlinAttributeMetadataIsReconstructedAndEditedWithoutTouchingCustomAnnotations() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/PayrollMetadata.java",
                """
                package com.acme.entity;

                import io.jmix.core.entity.annotation.SystemLevel;
                import io.jmix.core.metamodel.annotation.Comment;
                import io.jmix.core.metamodel.annotation.DependsOnProperties;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import io.jmix.core.metamodel.annotation.JmixProperty;
                import io.jmix.core.metamodel.annotation.PropertyDatatype;
                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Lob;
                import jakarta.persistence.Table;
                import jakarta.validation.constraints.Size;
                import java.util.UUID;

                @JmixEntity
                @Entity
                @Table(name = "PAYROLL_METADATA")
                public class PayrollMetadata {
                    @Id
                    private UUID id;

                    @CustomPayrollAudit(level = "strict")
                    @Comment("Original caption")
                    @SystemLevel
                    @Lob
                    @JmixProperty
                    @DependsOnProperties({"firstName", "lastName"})
                    @PropertyDatatype("payrollText")
                    @Size(min = 2, max = 64, message = "invalid payroll label", groups = {PayrollChecks.class})
                    @Column(name = "DISPLAY_NAME", nullable = false, length = 64, columnDefinition = "CLOB")
                    private String displayName;

                    public String manualLedgerKey() {
                        return "LEDGER-" + displayName;
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinPayrollMetadata.kt",
                """
                package com.acme.entity

                import io.jmix.core.metamodel.annotation.Comment
                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.Column
                import jakarta.persistence.Entity
                import jakarta.persistence.Id
                import jakarta.persistence.Table
                import jakarta.validation.constraints.Size
                import java.util.UUID

                @JmixEntity
                @Entity
                @Table(name = "KOTLIN_PAYROLL_METADATA")
                open class KotlinPayrollMetadata {
                    @Id
                    var id: UUID? = null

                    @CustomKotlinMetadata
                    @Comment("Original Kotlin note")
                    @Size(min = 1, max = 30)
                    @Column(name = "NOTE", length = 30)
                    var note: String? = null

                    fun manualNote(): String = note ?: "none"
                }
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val javaSnapshot = workspace.entities.single { it.className == "PayrollMetadata" }
        val kotlinSnapshot = workspace.entities.single { it.className == "KotlinPayrollMetadata" }
        val javaAttribute = javaSnapshot.attributes.single { it.name == "displayName" }
        val kotlinAttribute = kotlinSnapshot.attributes.single { it.name == "note" }

        assertEquals("Original caption", javaAttribute.comment)
        assertTrue(javaAttribute.systemLevel)
        assertTrue(javaAttribute.lob)
        assertTrue(javaAttribute.jmixProperty)
        assertEquals(listOf("firstName", "lastName"), javaAttribute.dependsOnProperties)
        assertEquals("payrollText", javaAttribute.propertyDatatype)
        assertEquals("CLOB", javaAttribute.sqlType)
        assertEquals(listOf("CustomPayrollAudit"), javaAttribute.unmanagedAnnotations)
        val javaSize = javaAttribute.validations.single()
        assertEquals(ValidationType.SIZE, javaSize.type)
        assertEquals("2", javaSize.value)
        assertEquals("64", javaSize.value2)
        assertEquals("invalid payroll label", javaSize.message)
        assertEquals(listOf("PayrollChecks"), javaSize.groups)
        assertEquals("Original Kotlin note", kotlinAttribute.comment)
        assertEquals(listOf("CustomKotlinMetadata"), kotlinAttribute.unmanagedAnnotations)

        val javaDesired = EntityModel(
            className = javaSnapshot.className,
            packageName = javaSnapshot.qualifiedName.substringBeforeLast('.'),
            entityName = javaSnapshot.entityName,
            tableName = javaSnapshot.tableName,
            attributes = mutableListOf(
                AttributeModel(
                    name = "displayName",
                    type = AttributeType.STRING,
                    columnName = "DISPLAY_NAME",
                    mandatory = true,
                    length = 64,
                    comment = "Reviewed payroll caption",
                    lob = true,
                    dependsOnProperties = mutableListOf("employeeNo"),
                    propertyDatatype = "reviewedPayrollText",
                    validations = mutableListOf(
                        ValidationModel(
                            ValidationType.SIZE,
                            value = "3",
                            value2 = "80",
                            message = "reviewed payroll label",
                            groups = mutableListOf("PayrollChecks"),
                        ),
                        ValidationModel(ValidationType.NOT_BLANK),
                    ),
                ),
            ),
            ddlGeneration = DdlGenerationConfig(false, DdlGenerationMode.DISABLED),
        )
        val javaPreview = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(javaSnapshot.sourceLocator, javaDesired),
        )
        assertTrue(javaPreview.accepted, javaPreview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(1, javaPreview.files.size)
        val java = javaPreview.files.single().resultContent
        assertTrue(java.contains("@CustomPayrollAudit(level = \"strict\")"))
        assertTrue(java.contains("@Comment(\"Reviewed payroll caption\")"))
        assertFalse(java.contains("@SystemLevel"))
        assertFalse(java.contains("@JmixProperty"))
        assertTrue(java.contains("@Lob"))
        assertTrue(java.contains("@DependsOnProperties({\"employeeNo\"})"))
        assertTrue(java.contains("@PropertyDatatype(\"reviewedPayrollText\")"))
        assertTrue(java.contains("@Size(min = 3, max = 80, message = \"reviewed payroll label\", groups = {PayrollChecks.class})"))
        assertTrue(java.contains("@NotBlank"))
        assertTrue(java.contains("columnDefinition = \"CLOB\""))
        assertTrue(java.contains("public String manualLedgerKey()"))

        val kotlinDesired = EntityModel(
            className = kotlinSnapshot.className,
            packageName = kotlinSnapshot.qualifiedName.substringBeforeLast('.'),
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            entityName = kotlinSnapshot.entityName,
            tableName = kotlinSnapshot.tableName,
            attributes = mutableListOf(
                AttributeModel(
                    name = "note",
                    type = AttributeType.STRING,
                    columnName = "NOTE",
                    length = 30,
                    comment = "Reviewed Kotlin note",
                    jmixProperty = true,
                    validations = mutableListOf(
                        ValidationModel(ValidationType.SIZE, value = "2", value2 = "40"),
                        ValidationModel(ValidationType.NOT_BLANK),
                    ),
                ),
            ),
            ddlGeneration = DdlGenerationConfig(false, DdlGenerationMode.DISABLED),
        )
        val kotlinPreview = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(kotlinSnapshot.sourceLocator, kotlinDesired),
        )
        assertTrue(kotlinPreview.accepted, kotlinPreview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(1, kotlinPreview.files.size)
        val kotlin = kotlinPreview.files.single().resultContent
        assertTrue(kotlin.contains("@CustomKotlinMetadata"))
        assertTrue(kotlin.contains("@Comment(\"Reviewed Kotlin note\")"))
        assertTrue(kotlin.contains("@JmixProperty"))
        assertTrue(kotlin.contains("@Size(min = 2, max = 40)"))
        assertTrue(kotlin.contains("@NotBlank"))
        assertTrue(kotlin.contains("fun manualNote(): String"))
    }

    fun testExistingKotlinEntityAdditionPreservesManualSourceAndAddsRollbackMigration() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinLoanAccount.kt",
                """
                    package com.acme.entity

                    import io.jmix.core.metamodel.annotation.JmixEntity
                    import jakarta.persistence.Column
                    import jakarta.persistence.Entity
                    import jakarta.persistence.Id
                    import jakarta.persistence.Table
                    import java.util.UUID

                    @JmixEntity
                    @Entity
                    @Table(name = "KOTLIN_LOAN_ACCOUNT")
                    open class KotlinLoanAccount {
                        @Id
                        @Column(name = "ID", nullable = false)
                        var id: UUID? = null

                        @Column(name = "ACCOUNT_NO", nullable = false, length = 64)
                        var accountNo: String? = null

                        fun manualRiskScore(): Int = 73
                    }
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val snapshot = workspace.entities.single { it.className == "KotlinLoanAccount" }
        val accountNo = snapshot.attributes.single { it.name == "accountNo" }
        val store = workspace.stores.single()
        assertEquals("ACCOUNT_NO", accountNo.columnName)
        assertFalse(accountNo.nullable)
        assertEquals(64, accountNo.length)
        assertEquals(IdType.UUID, snapshot.idType)
        val rename = EntityAttributeRefactorService.getInstance(project).prepareRename(
            EntityAttributeRenameRequest(
                sourceLocator = snapshot.sourceLocator,
                entityClassName = snapshot.className,
                attributeName = "accountNo",
                newName = "externalAccountNo",
            ),
        )
        assertTrue(rename.accepted, "${rename.code}: ${rename.message}")
        assertEquals("KtProperty", rename.element?.javaClass?.simpleName)

        val entity = EntityModel(
            className = snapshot.className,
            packageName = snapshot.qualifiedName.substringBeforeLast('.'),
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            tableName = snapshot.tableName,
            dataStore = snapshot.storeName,
            generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = "accountNo",
                    type = AttributeType.STRING,
                    columnName = "EXTERNAL_ACCOUNT_NO",
                    mandatory = true,
                    unique = true,
                    length = 128,
                ),
                AttributeModel(
                    name = "approvedAmount",
                    type = AttributeType.BIG_DECIMAL,
                    columnName = "APPROVED_AMOUNT",
                    precision = 19,
                    scale = 2,
                ),
            ),
        )
        val preview = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(snapshot.sourceLocator, entity),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(2, preview.files.size)
        val kotlin = preview.files.single { it.relativePath.endsWith(".kt") }.resultContent
        assertTrue(kotlin.contains("fun manualRiskScore(): Int = 73"))
        assertTrue(kotlin.contains("import java.math.BigDecimal"))
        assertTrue(
            kotlin.contains(
                "@Column(name = \"EXTERNAL_ACCOUNT_NO\", nullable = false, unique = true, length = 128)",
            ),
        )
        assertTrue(kotlin.contains("@Column(name = \"APPROVED_AMOUNT\", precision = 19, scale = 2)"))
        assertTrue(kotlin.contains("var approvedAmount: BigDecimal? = null"))
        val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
        assertTrue(migration.contains("<addColumn tableName=\"KOTLIN_LOAN_ACCOUNT\">"))
        assertTrue(migration.contains("<addUniqueConstraint tableName=\"KOTLIN_LOAN_ACCOUNT\""))
        assertTrue(
            migration.contains(
                "<renameColumn tableName=\"KOTLIN_LOAN_ACCOUNT\" oldColumnName=\"ACCOUNT_NO\" " +
                    "newColumnName=\"EXTERNAL_ACCOUNT_NO\"",
            ),
        )
        assertTrue(
            migration.contains(
                "<columnExists tableName=\"KOTLIN_LOAN_ACCOUNT\" columnName=\"ACCOUNT_NO\"",
            ),
        )
        assertTrue(
            migration.contains(
                "<columnExists tableName=\"KOTLIN_LOAN_ACCOUNT\" columnName=\"EXTERNAL_ACCOUNT_NO\"",
            ),
        )
        assertTrue(migration.contains("newDataType=\"VARCHAR(128)\""))
        assertTrue(migration.contains("JVW_DUPLICATES"))
        assertTrue(
            migration.contains(
                "<renameColumn tableName=\"KOTLIN_LOAN_ACCOUNT\" oldColumnName=\"EXTERNAL_ACCOUNT_NO\" " +
                    "newColumnName=\"ACCOUNT_NO\"",
            ),
        )
        assertTrue(migration.contains("<dropColumn tableName=\"KOTLIN_LOAN_ACCOUNT\" columnName=\"APPROVED_AMOUNT\""))

        val prepared = ExistingEntityChangeService.getInstance(project).prepareAttributeAdditions(
            ExistingEntityAttributeAdditionApplyRequest(
                change = ExistingEntityAttributeAdditionRequest(snapshot.sourceLocator, entity),
                expectedPlanDigest = requireNotNull(preview.planDigest),
            ),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        val refreshed = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
            .entities.single { it.className == "KotlinLoanAccount" }
        assertEquals(
            "EXTERNAL_ACCOUNT_NO",
            refreshed.attributes.single { it.name == "accountNo" }.columnName,
        )
        val coordinatedRename = EntityAttributeRefactorService.getInstance(project).prepareRename(
            EntityAttributeRenameRequest(
                sourceLocator = refreshed.sourceLocator,
                entityClassName = refreshed.className,
                attributeName = "accountNo",
                newName = "externalAccountNo",
            ),
        )
        assertTrue(
            coordinatedRename.accepted,
            "${coordinatedRename.code}: ${coordinatedRename.message}",
        )
        assertEquals("KtProperty", coordinatedRename.element?.javaClass?.simpleName)
    }

    fun testExistingEntityCrossStoreRelationshipIsAddedWithoutRewritingManualCodeOrAddingForeignKey() {
        createFixture(includeAll = true)
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val snapshot = workspace.entities.single { it.className == "LoanApp" }
        val store = workspace.stores.single()
        val entity = EntityModel(
            className = snapshot.className,
            packageName = snapshot.qualifiedName.substringBeforeLast('.'),
            tableName = snapshot.tableName,
            dataStore = snapshot.storeName,
            generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = "applicationNo",
                    type = AttributeType.STRING,
                    columnName = "APPLICATION_NO",
                    mandatory = true,
                ),
                AttributeModel(
                    name = "loanAmount",
                    type = AttributeType.BIG_DECIMAL,
                    columnName = "LOAN_AMOUNT",
                    mandatory = true,
                    precision = 19,
                    scale = 2,
                ),
                AttributeModel(
                    name = "fundProfile",
                    type = AttributeType.ASSOCIATION,
                    mandatory = true,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = "com.acme.fund.FundProfile",
                        relatedTableName = "FUND_PROFILE",
                        relatedIdType = IdType.UUID,
                        localIdAttributeName = "fundProfileId",
                        joinColumnName = "FUND_PROFILE_ID",
                        crossDataStore = true,
                    ),
                ),
            ),
        )

        val preview = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(snapshot.sourceLocator, entity),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val java = preview.files.single { it.relativePath.endsWith("LoanApp.java") }.resultContent
        assertTrue(java.contains("public int calculateRisk()"))
        assertTrue(java.contains("protected UUID fundProfileId;"))
        assertTrue(java.contains("@DependsOnProperties(\"fundProfileId\")"))
        assertTrue(java.contains("protected FundProfile fundProfile;"))
        val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
        assertTrue(migration.contains("<addColumn tableName=\"LOAN_APP\">"))
        assertTrue(migration.contains("<column name=\"FUND_PROFILE_ID\" type=\"UUID\""))
        assertFalse(migration.contains("<addForeignKeyConstraint"))
        assertTrue(migration.contains("<dropColumn tableName=\"LOAN_APP\" columnName=\"FUND_PROFILE_ID\""))
    }

    fun testExistingEntitySafeMetadataUpdatePreservesManualCodeAndGeneratesCheckedRollback() {
        createFixture(includeAll = true)
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val snapshot = workspace.entities.single { it.className == "LoanApp" }
        val store = workspace.stores.single()
        val entity = EntityModel(
            className = snapshot.className,
            packageName = snapshot.qualifiedName.substringBeforeLast('.'),
            tableName = snapshot.tableName,
            dataStore = snapshot.storeName,
            generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
            attributes = mutableListOf(
                AttributeModel(
                    name = "applicationNo",
                    type = AttributeType.STRING,
                    columnName = "BUSINESS_APPLICATION_NO",
                    mandatory = true,
                    unique = true,
                    length = 512,
                ),
                AttributeModel(
                    name = "loanAmount",
                    type = AttributeType.BIG_DECIMAL,
                    columnName = "LOAN_AMOUNT",
                    mandatory = false,
                    precision = 24,
                    scale = 2,
                ),
            ),
        )

        val preview = ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(snapshot.sourceLocator, entity),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(2, preview.files.size)
        val java = preview.files.single { it.relativePath.endsWith("LoanApp.java") }.resultContent
        assertTrue(java.contains("public int calculateRisk()"))
        assertTrue(
            java.contains(
                "@Column(name = \"BUSINESS_APPLICATION_NO\", nullable = false, unique = true, length = 512)",
            ),
        )
        assertTrue(java.contains("@Column(name = \"LOAN_AMOUNT\", precision = 24, scale = 2)"))
        val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
        assertTrue(migration.contains("<sqlCheck expectedResult=\"0\">"))
        assertTrue(migration.contains("JVW_DUPLICATES"))
        assertTrue(
            migration.contains(
                "<renameColumn tableName=\"LOAN_APP\" oldColumnName=\"APPLICATION_NO\" " +
                    "newColumnName=\"BUSINESS_APPLICATION_NO\"",
            ),
        )
        assertTrue(
            migration.contains(
                "<columnExists tableName=\"LOAN_APP\" columnName=\"APPLICATION_NO\"",
            ),
        )
        assertTrue(
            migration.contains(
                "<columnExists tableName=\"LOAN_APP\" columnName=\"BUSINESS_APPLICATION_NO\"",
            ),
        )
        assertTrue(migration.contains("<addUniqueConstraint tableName=\"LOAN_APP\""))
        assertTrue(migration.contains("<dropNotNullConstraint tableName=\"LOAN_APP\" columnName=\"LOAN_AMOUNT\""))
        assertTrue(migration.contains("newDataType=\"VARCHAR(512)\""))
        assertTrue(migration.contains("newDataType=\"DECIMAL(24, 2)\""))
        assertTrue(migration.contains("<rollback>"))
        assertTrue(migration.contains("<dropUniqueConstraint tableName=\"LOAN_APP\""))
        assertTrue(migration.contains("<addNotNullConstraint tableName=\"LOAN_APP\" columnName=\"LOAN_AMOUNT\""))
        assertTrue(
            migration.contains(
                "<renameColumn tableName=\"LOAN_APP\" oldColumnName=\"BUSINESS_APPLICATION_NO\" " +
                    "newColumnName=\"APPLICATION_NO\"",
            ),
        )
    }

    fun testExistingJavaAndKotlinRelationshipRenameRequiresStablePhysicalMapping() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/Department.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "DEPARTMENT")
                    public class Department {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/Employee.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "EMPLOYEE")
                    public class Employee {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                        @ManyToOne
                        @JoinColumn(name = "DEPARTMENT_ID")
                        private Department department;
                        @Column(name = "AGE")
                        private Integer age;
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/UnsafeEmployee.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "UNSAFE_EMPLOYEE")
                    public class UnsafeEmployee {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                        @ManyToOne
                        private Department department;
                        private Integer age;
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinEmployee.kt",
                """
                    package com.acme.entity
                    import io.jmix.core.metamodel.annotation.JmixEntity
                    import jakarta.persistence.*
                    import java.util.UUID
                    @JmixEntity
                    @Entity
                    @Table(name = "KOTLIN_EMPLOYEE")
                    class KotlinEmployee {
                        @Id
                        @Column(name = "ID")
                        var id: UUID? = null
                        @ManyToOne
                        @JoinColumn(name = "DEPARTMENT_ID")
                        var department: Department? = null
                        @Column(name = "AGE")
                        var age: Int? = null
                    }
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val refactors = EntityAttributeRefactorService.getInstance(project)

        val employee = workspace.entities.single { it.className == "Employee" }
        val javaRename = refactors.prepareRename(
            EntityAttributeRenameRequest(
                employee.sourceLocator,
                employee.className,
                "department",
                "organizationalUnit",
            ),
        )
        assertTrue(javaRename.accepted, "${javaRename.code}: ${javaRename.message}")
        val javaSafeDelete = refactors.prepareSafeDelete(
            EntityAttributeSafeDeleteRequest(
                employee.sourceLocator,
                employee.className,
                "department",
            ),
        )
        assertTrue(
            javaSafeDelete.accepted,
            "${javaSafeDelete.code}: ${javaSafeDelete.message}",
        )
        assertEquals("DEPARTMENT_ID", javaSafeDelete.retainedColumnName)
        val javaTypeMigration = refactors.prepareTypeMigration(
            EntityAttributeTypeMigrationRequest(
                employee.sourceLocator,
                employee.className,
                "age",
                AttributeType.LONG,
            ),
        )
        assertTrue(
            javaTypeMigration.accepted,
            "${javaTypeMigration.code}: ${javaTypeMigration.message}",
        )
        assertTrue(javaTypeMigration.element is com.intellij.psi.PsiField)
        assertNotNull(javaTypeMigration.targetPsiType)
        assertEquals(
            EntityAttributeTypeSchemaStrategy.SCHEMA_EVIDENCE_INCOMPLETE,
            javaTypeMigration.schemaImpact?.strategy,
        )

        val kotlinEmployee = workspace.entities.single { it.className == "KotlinEmployee" }
        val kotlinRename = refactors.prepareRename(
            EntityAttributeRenameRequest(
                kotlinEmployee.sourceLocator,
                kotlinEmployee.className,
                "department",
                "organizationalUnit",
            ),
        )
        assertTrue(kotlinRename.accepted, "${kotlinRename.code}: ${kotlinRename.message}")
        assertEquals("KtProperty", kotlinRename.element?.javaClass?.simpleName)
        val kotlinSafeDelete = refactors.prepareSafeDelete(
            EntityAttributeSafeDeleteRequest(
                kotlinEmployee.sourceLocator,
                kotlinEmployee.className,
                "department",
            ),
        )
        assertTrue(
            kotlinSafeDelete.accepted,
            "${kotlinSafeDelete.code}: ${kotlinSafeDelete.message}",
        )
        assertEquals("DEPARTMENT_ID", kotlinSafeDelete.retainedColumnName)
        val kotlinTypeMigration = refactors.prepareTypeMigration(
            EntityAttributeTypeMigrationRequest(
                kotlinEmployee.sourceLocator,
                kotlinEmployee.className,
                "age",
                AttributeType.LONG,
            ),
        )
        assertTrue(
            kotlinTypeMigration.accepted,
            "${kotlinTypeMigration.code}: ${kotlinTypeMigration.message}",
        )
        assertTrue(kotlinTypeMigration.element is com.intellij.psi.PsiField)
        assertNotNull(kotlinTypeMigration.targetPsiType)
        val kotlinSourceFile = requireNotNull(
            ProjectFileResolver.getInstance(project)
                .resolveFile(kotlinEmployee.sourceLocator.relativePath)?.file,
        )
        val kotlinSource = ProjectSourceText.read(kotlinSourceFile)
        val kotlinMappingEdit = requireNotNull(
            EntityAttributeTypeCutoverService.getInstance(project).columnEditFromPsi(
                psiFile = requireNotNull(PsiManager.getInstance(project).findFile(kotlinSourceFile)),
                fileExtension = "kt",
                className = kotlinEmployee.className,
                attributeName = "age",
                expectedColumnName = "AGE",
                replacementColumnName = "JVE_TEST_AGE",
            ),
        )
        assertEquals("AGE", kotlinMappingEdit.expectedText)
        assertEquals("JVE_TEST_AGE", kotlinMappingEdit.replacement)
        assertTrue(
            kotlinSource.replaceRange(
                kotlinMappingEdit.startOffset,
                kotlinMappingEdit.endOffset,
                kotlinMappingEdit.replacement,
            ).contains("""@Column(name = "JVE_TEST_AGE")"""),
        )

        val unsafe = workspace.entities.single { it.className == "UnsafeEmployee" }
        val rejected = refactors.prepareRename(
            EntityAttributeRenameRequest(
                unsafe.sourceLocator,
                unsafe.className,
                "department",
                "organizationalUnit",
            ),
        )
        assertFalse(rejected.accepted)
        assertEquals("JVW-ENTITY-RENAME-INFERRED-RELATIONSHIP-MAPPING", rejected.code)
        val unsafeSafeDelete = refactors.prepareSafeDelete(
            EntityAttributeSafeDeleteRequest(
                unsafe.sourceLocator,
                unsafe.className,
                "department",
            ),
        )
        assertFalse(unsafeSafeDelete.accepted)
        assertEquals(
            "JVW-ENTITY-SAFE-DELETE-INFERRED-RELATIONSHIP-MAPPING",
            unsafeSafeDelete.code,
        )
        val unsafeTypeMigration = refactors.prepareTypeMigration(
            EntityAttributeTypeMigrationRequest(
                unsafe.sourceLocator,
                unsafe.className,
                "age",
                AttributeType.LONG,
            ),
        )
        assertFalse(unsafeTypeMigration.accepted)
        assertEquals(
            "JVW-ENTITY-TYPE-MIGRATION-INFERRED-COLUMN",
            unsafeTypeMigration.code,
        )
    }

    fun testEntityTypeMigrationReportsPhysicalConversionAndIndexDependencies() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/LoanApp.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity
                    @Entity
                    @Table(name = "LOAN_APP")
                    public class LoanApp {
                        @Id
                        private java.util.UUID id;
                        @Column(name = "APPLICATION_NO", nullable = false)
                        private String applicationNo;
                        @Column(name = "LOAN_AMOUNT", nullable = false, precision = 19, scale = 2)
                        private java.math.BigDecimal loanAmount;
                        @Column(name = "RISK_SCORE", nullable = false)
                        private Integer riskScore;
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog/020-entity-types.xml",
                """
                    <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                        <changeSet id="entity-types" author="team">
                            <addColumn tableName="LOAN_APP">
                                <column name="APPLICATION_NO" type="VARCHAR(255)"/>
                                <column name="LOAN_AMOUNT" type="DECIMAL(19, 2)"/>
                                <column name="RISK_SCORE" type="INT">
                                    <constraints nullable="false"/>
                                </column>
                            </addColumn>
                            <createIndex tableName="LOAN_APP" indexName="IDX_LOAN_APP_AMOUNT">
                                <column name="LOAN_AMOUNT"/>
                            </createIndex>
                        </changeSet>
                    </databaseChangeLog>
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val loanApp = workspace.entities.single { it.className == "LoanApp" }
        val refactors = EntityAttributeRefactorService.getInstance(project)

        val sourceOnly = refactors.prepareTypeMigration(
            EntityAttributeTypeMigrationRequest(
                loanApp.sourceLocator,
                loanApp.className,
                "applicationNo",
                AttributeType.URI,
            ),
        )
        assertTrue(sourceOnly.accepted, "${sourceOnly.code}: ${sourceOnly.message}")
        assertEquals(
            EntityAttributeTypeSchemaStrategy.SOURCE_ONLY,
            sourceOnly.schemaImpact?.strategy,
        )
        assertEquals("VARCHAR(255)", sourceOnly.schemaImpact?.currentSqlType)
        assertEquals("VARCHAR(255)", sourceOnly.schemaImpact?.targetSqlType)

        val conversion = refactors.prepareTypeMigration(
            EntityAttributeTypeMigrationRequest(
                loanApp.sourceLocator,
                loanApp.className,
                "loanAmount",
                AttributeType.DOUBLE,
            ),
        )
        assertTrue(conversion.accepted, "${conversion.code}: ${conversion.message}")
        assertEquals(
            EntityAttributeTypeSchemaStrategy.EXPAND_CONTRACT_REQUIRED,
            conversion.schemaImpact?.strategy,
        )
        assertEquals("DECIMAL(19, 2)", conversion.schemaImpact?.currentSqlType)
        assertEquals("DOUBLE", conversion.schemaImpact?.targetSqlType)
        assertTrue(
            conversion.schemaImpact?.dependencies?.contains("index IDX_LOAN_APP_AMOUNT") == true,
        )
        assertTrue(conversion.schemaImpact?.summary?.contains("not automatically reversible") == true)

        val expansionRequest = EntityAttributeTypeMigrationRequest(
            loanApp.sourceLocator,
            loanApp.className,
            "riskScore",
            AttributeType.LONG,
        )
        val expansion = EntityAttributeTypeExpansionService.getInstance(project)
            .build(expansionRequest)
        val expansionMigration = assertNotNull(
            expansion.migration,
            "${expansion.code}: ${expansion.message}",
        )
        val expansionXml = MigrationGenerator.generate(expansionMigration.migration)
        assertTrue(expansionXml.contains("""onFail="HALT""""))
        assertTrue(expansionXml.contains("""onError="HALT""""))
        assertTrue(expansionXml.contains("""<addColumn tableName="LOAN_APP""""))
        assertTrue(expansionXml.contains("""valueComputed="RISK_SCORE""""))
        assertTrue(expansionXml.contains("<where>"))
        assertTrue(expansionXml.contains("RISK_SCORE IS NOT NULL"))
        assertTrue(expansionXml.contains("<addNotNullConstraint"))
        assertTrue(expansionXml.contains("<rollback>"))
        assertTrue(expansionXml.contains("<dropColumn"))
        assertFalse(expansionXml.contains("<modifyDataType"))
        assertFalse(expansionXml.contains("""columnName="RISK_SCORE"</dropColumn"""))
        assertTrue(expansionXml.contains("<sqlCheck expectedResult=\"0\">"))
        assertTrue(expansionXml.contains("SET ${expansion.shadowColumnName} = NULL"))
        val description = EntityAttributeTypeExpansionService.getInstance(project)
            .describe(expansionRequest)
        val descriptor = assertNotNull(
            description.descriptor,
            "${description.code}: ${description.message}",
        )
        assertEquals(expansion.shadowColumnName, descriptor.shadowColumnName)
        assertEquals("RISK_SCORE", descriptor.originalColumnName)
        assertEquals("BIGINT", descriptor.targetSqlType)
        val loanSourceFile = requireNotNull(
            ProjectFileResolver.getInstance(project)
                .resolveFile(loanApp.sourceLocator.relativePath)?.file,
        )
        val loanSource = ProjectSourceText.read(loanSourceFile)
        val javaMappingEdit = requireNotNull(
            EntityAttributeTypeCutoverService.getInstance(project).columnEditFromPsi(
                psiFile = requireNotNull(PsiManager.getInstance(project).findFile(loanSourceFile)),
                fileExtension = "java",
                className = loanApp.className,
                attributeName = "riskScore",
                expectedColumnName = "RISK_SCORE",
                replacementColumnName = descriptor.shadowColumnName,
            ),
        )
        val cutoverSource = loanSource.replaceRange(
            javaMappingEdit.startOffset,
            javaMappingEdit.endOffset,
            javaMappingEdit.replacement,
        )
        assertTrue(cutoverSource.contains("""@Column(name = "${descriptor.shadowColumnName}", nullable = false)"""))
        assertTrue(cutoverSource.contains("private Integer riskScore;"))
        assertEquals(
            "RISK_SCORE".length,
            javaMappingEdit.endOffset - javaMappingEdit.startOffset,
        )

        val expansionPreview = EntityAttributeTypeExpansionService.getInstance(project)
            .preview(expansionRequest)
        assertTrue(
            expansionPreview.accepted,
            "${expansionPreview.code}: ${expansionPreview.message}",
        )
        assertNotNull(expansionPreview.preview.planDigest)
        assertEquals(1, expansionPreview.preview.files.size)
        assertTrue(expansionPreview.preview.files.single().resultContent.contains("<sqlCheck"))

        val unsafeExpansion = EntityAttributeTypeExpansionService.getInstance(project).build(
            EntityAttributeTypeMigrationRequest(
                loanApp.sourceLocator,
                loanApp.className,
                "loanAmount",
                AttributeType.DOUBLE,
            ),
        )
        assertEquals(
            "JVW-ENTITY-TYPE-EXPANSION-CONVERSION-REQUIRES-EXPRESSION",
            unsafeExpansion.code,
        )
    }

    fun testEstablishedBidirectionalCardinalityChangesUpdateBothSourcesAndSchemaAtomically() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/kotlin/com/acme/entity/CardinalityDepartment.kt",
                """
                    package com.acme.entity
                    import io.jmix.core.metamodel.annotation.JmixEntity
                    import jakarta.persistence.*
                    import java.util.UUID
                    @JmixEntity
                    @Entity
                    @Table(name = "CARDINALITY_DEPARTMENT")
                    open class CardinalityDepartment {
                        @Id
                        @Column(name = "ID", nullable = false)
                        var id: UUID? = null

                        @ManualInverseCollection("preserve")
                        @OneToMany(mappedBy = "department", fetch = FetchType.LAZY)
                        var employees: MutableList<CardinalityEmployee> = mutableListOf()

                        fun manualDepartmentCode(): String = "department"
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/CardinalityEmployee.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    import java.util.UUID;
                    @JmixEntity
                    @Entity
                    @Table(name = "CARDINALITY_EMPLOYEE")
                    public class CardinalityEmployee {
                        @Id
                        @Column(name = "ID", nullable = false)
                        private UUID id;

                        @ManyToOne(fetch = FetchType.LAZY)
                        @JoinColumn(
                            name = "DEPARTMENT_ID",
                            referencedColumnName = "ID",
                            foreignKey = @ForeignKey(name = "FK_CARDINALITY_EMPLOYEE_DEPARTMENT")
                        )
                        private CardinalityDepartment department;

                        public String manualEmployeeCode() { return "employee"; }
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/CardinalityAccount.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    import java.util.UUID;
                    @JmixEntity
                    @Entity
                    @Table(name = "CARDINALITY_ACCOUNT")
                    public class CardinalityAccount {
                        @Id
                        @Column(name = "ID", nullable = false)
                        private UUID id;

                        @ManualInverseScalar
                        @OneToOne(mappedBy = "account", fetch = FetchType.LAZY)
                        private CardinalityContract contracts = null;

                        public String manualAccountCode() { return "account"; }
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/CardinalityContract.kt",
                """
                    package com.acme.entity
                    import io.jmix.core.metamodel.annotation.JmixEntity
                    import jakarta.persistence.*
                    import java.util.UUID
                    @JmixEntity
                    @Entity
                    @Table(name = "CARDINALITY_CONTRACT")
                    open class CardinalityContract {
                        @Id
                        @Column(name = "ID", nullable = false)
                        var id: UUID? = null

                        @OneToOne(fetch = FetchType.LAZY)
                        @JoinColumn(
                            name = "ACCOUNT_ID",
                            referencedColumnName = "ID",
                            unique = true,
                            foreignKey = ForeignKey(name = "FK_CARDINALITY_CONTRACT_ACCOUNT"),
                        )
                        var account: CardinalityAccount? = null

                        fun manualContractCode(): String = "contract"
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog/024-bidirectional-cardinality.xml",
                """
                    <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                        <changeSet id="bidirectional-cardinality" author="team">
                            <createTable tableName="CARDINALITY_DEPARTMENT">
                                <column name="ID" type="UUID">
                                    <constraints nullable="false" primaryKey="true"/>
                                </column>
                            </createTable>
                            <createTable tableName="CARDINALITY_EMPLOYEE">
                                <column name="ID" type="UUID">
                                    <constraints nullable="false" primaryKey="true"/>
                                </column>
                                <column name="DEPARTMENT_ID" type="UUID"/>
                            </createTable>
                            <addForeignKeyConstraint
                                constraintName="FK_CARDINALITY_EMPLOYEE_DEPARTMENT"
                                baseTableName="CARDINALITY_EMPLOYEE"
                                baseColumnNames="DEPARTMENT_ID"
                                referencedTableName="CARDINALITY_DEPARTMENT"
                                referencedColumnNames="ID"/>
                            <createTable tableName="CARDINALITY_ACCOUNT">
                                <column name="ID" type="UUID">
                                    <constraints nullable="false" primaryKey="true"/>
                                </column>
                            </createTable>
                            <createTable tableName="CARDINALITY_CONTRACT">
                                <column name="ID" type="UUID">
                                    <constraints nullable="false" primaryKey="true"/>
                                </column>
                                <column name="ACCOUNT_ID" type="UUID"/>
                            </createTable>
                            <addUniqueConstraint
                                tableName="CARDINALITY_CONTRACT"
                                constraintName="UQ_CARDINALITY_CONTRACT_ACCOUNT"
                                columnNames="ACCOUNT_ID"/>
                            <addForeignKeyConstraint
                                constraintName="FK_CARDINALITY_CONTRACT_ACCOUNT"
                                baseTableName="CARDINALITY_CONTRACT"
                                baseColumnNames="ACCOUNT_ID"
                                referencedTableName="CARDINALITY_ACCOUNT"
                                referencedColumnNames="ID"/>
                        </changeSet>
                    </databaseChangeLog>
                """.trimIndent(),
            )
        }

        val schemaService = SchemaWorkspaceService.getInstance(project)
        val workspace = schemaService.load(forceRefresh = true)
        val store = workspace.stores.single()
        val service = ExistingEntityChangeService.getInstance(project)

        fun model(
            snapshot: SchemaEntitySnapshot,
            attributeName: String,
            targetType: AssociationType,
            targetUnique: Boolean,
        ): EntityModel {
            val current = snapshot.attributes.single { it.name == attributeName }
            val relation = requireNotNull(current.associationDetails)
            return EntityModel(
                className = snapshot.className,
                packageName = snapshot.qualifiedName.substringBeforeLast('.'),
                sourceLanguage = if (snapshot.sourceLocator.relativePath.endsWith(".kt")) {
                    EntitySourceLanguage.KOTLIN
                } else {
                    EntitySourceLanguage.JAVA
                },
                tableName = snapshot.tableName,
                dataStore = snapshot.storeName,
                generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
                attributes = mutableListOf(
                    AttributeModel(
                        name = current.name,
                        type = AttributeType.ASSOCIATION,
                        columnName = current.columnName,
                        mandatory = !current.nullable,
                        unique = targetUnique,
                        association = AssociationConfig(
                            associationType = targetType,
                            relatedEntity = relation.relatedEntity,
                            relatedTableName = relation.relatedTableName,
                            relatedIdColumnName = relation.relatedIdColumnName,
                            relatedIdType = relation.relatedIdType,
                            localIdAttributeName = relation.localIdAttributeName,
                            mappedBy = relation.mappedBy,
                            joinColumnName = relation.joinColumnName,
                            joinTable = relation.joinTable,
                            cascade = relation.cascade.toMutableList(),
                            fetch = relation.fetch,
                            collectionType = relation.collectionType,
                            crossDataStore = relation.crossDataStore,
                            orphanRemoval = relation.orphanRemoval,
                            onDelete = relation.onDelete,
                        ),
                    ),
                ),
            )
        }

        val employee = workspace.entities.single { it.className == "CardinalityEmployee" }
        val narrowingRequest = ExistingEntityAttributeAdditionRequest(
            employee.sourceLocator,
            model(
                employee,
                "department",
                AssociationType.ONE_TO_ONE,
                targetUnique = true,
            ),
        )
        val narrowing = service.previewAttributeAdditions(narrowingRequest)
        assertTrue(narrowing.accepted, narrowing.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(3, narrowing.files.size)
        val employeeSource = narrowing.files.single {
            it.relativePath.endsWith("/CardinalityEmployee.java")
        }.resultContent
        val departmentSource = narrowing.files.single {
            it.relativePath.endsWith("/CardinalityDepartment.kt")
        }.resultContent
        val narrowingMigration = narrowing.files.single {
            it.relativePath.endsWith(".xml")
        }.resultContent
        assertTrue(employeeSource.contains("@OneToOne(fetch = FetchType.LAZY)"))
        assertFalse(employeeSource.contains("@ManyToOne"))
        assertTrue(employeeSource.contains("unique = true"))
        assertTrue(employeeSource.contains("manualEmployeeCode"))
        assertTrue(departmentSource.contains("@ManualInverseCollection(\"preserve\")"))
        assertTrue(departmentSource.contains("@OneToOne(mappedBy = \"department\", fetch = FetchType.LAZY)"))
        assertTrue(departmentSource.contains("var employees: CardinalityEmployee? = null"))
        assertFalse(departmentSource.contains("MutableList<CardinalityEmployee>"))
        assertTrue(departmentSource.contains("manualDepartmentCode"))
        assertTrue(narrowingMigration.contains("HAVING COUNT(*) &gt; 1"))
        assertTrue(narrowingMigration.contains("""<addUniqueConstraint tableName="CARDINALITY_EMPLOYEE""""))
        assertFalse(narrowingMigration.contains("""tableName="CARDINALITY_DEPARTMENT""""))

        val contract = workspace.entities.single { it.className == "CardinalityContract" }
        val account = workspace.entities.single { it.className == "CardinalityAccount" }
        val accountInverse = requireNotNull(
            account.attributes.single { it.name == "contracts" }.associationDetails,
        )
        assertEquals(AssociationType.ONE_TO_ONE, accountInverse.associationType)
        assertEquals(contract.qualifiedName, accountInverse.relatedEntity)
        assertEquals("account", accountInverse.mappedBy)
        val wideningRequest = ExistingEntityAttributeAdditionRequest(
            contract.sourceLocator,
            model(
                contract,
                "account",
                AssociationType.MANY_TO_ONE,
                targetUnique = false,
            ),
        )
        val widening = service.previewAttributeAdditions(wideningRequest)
        assertTrue(widening.accepted, widening.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(
            widening.files.joinToString { it.relativePath },
            3,
            widening.files.size,
        )
        val contractSource = widening.files.single {
            it.relativePath.endsWith("/CardinalityContract.kt")
        }.resultContent
        val accountSource = widening.files.single {
            it.relativePath.endsWith("/CardinalityAccount.java")
        }.resultContent
        val wideningMigration = widening.files.single {
            it.relativePath.endsWith(".xml")
        }.resultContent
        assertTrue(contractSource.contains("@ManyToOne(fetch = FetchType.LAZY)"))
        assertFalse(contractSource.contains("@OneToOne"))
        assertFalse(contractSource.contains("unique = true"))
        assertTrue(contractSource.contains("manualContractCode"))
        assertTrue(accountSource.contains("@ManualInverseScalar"))
        assertTrue(accountSource.contains("@OneToMany(mappedBy = \"account\", fetch = FetchType.LAZY)"))
        assertTrue(accountSource.contains("List<CardinalityContract> contracts = new java.util.ArrayList<>()"))
        assertTrue(accountSource.contains("import java.util.List;"))
        assertTrue(accountSource.contains("manualAccountCode"))
        assertTrue(
            wideningMigration.contains(
                """<dropUniqueConstraint tableName="CARDINALITY_CONTRACT" """ +
                    """constraintName="UQ_CARDINALITY_CONTRACT_ACCOUNT"""",
            ),
        )
        assertTrue(wideningMigration.contains("<rollback>"))
        assertTrue(
            wideningMigration.contains(
                """<addUniqueConstraint tableName="CARDINALITY_CONTRACT" """ +
                    """constraintName="UQ_CARDINALITY_CONTRACT_ACCOUNT"""",
            ),
        )

        val prepared = service.prepareAttributeAdditions(
            ExistingEntityAttributeAdditionApplyRequest(
                change = narrowingRequest,
                expectedPlanDigest = requireNotNull(narrowing.planDigest),
            ),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(3, applied.filesChanged.size)
        val refreshed = schemaService.load(forceRefresh = true)
        assertEquals(
            AssociationType.ONE_TO_ONE,
            refreshed.entities.single { it.className == "CardinalityEmployee" }
                .attributes.single { it.name == "department" }
                .associationDetails?.associationType,
        )
        assertEquals(
            AssociationType.ONE_TO_ONE,
            refreshed.entities.single { it.className == "CardinalityDepartment" }
                .attributes.single { it.name == "employees" }
                .associationDetails?.associationType,
        )
        assertTrue(
            refreshed.physicalSchemas.single { it.storeId == store.id }
                .tables.single { it.name == "CARDINALITY_EMPLOYEE" }
                .columns.single { it.name == "DEPARTMENT_ID" }
                .unique,
        )
    }

    fun testExistingJavaAndKotlinOwningJoinColumnRenameIsSourceSafeAndRollbackChecked() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/Department.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "DEPARTMENT")
                    public class Department {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/Employee.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "EMPLOYEE")
                    public class Employee {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                        @ManyToOne
                        @JoinColumn(
                            name = "DEPARTMENT_ID",
                            referencedColumnName = "ID",
                            nullable = false,
                            foreignKey = @ForeignKey(name = "FK_EMPLOYEE_DEPARTMENT")
                        )
                        private Department department;
                        public String manualLabel() { return "employee"; }
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/UnsafeEmployee.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "UNSAFE_EMPLOYEE")
                    public class UnsafeEmployee {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                        @ManyToOne
                        private Department department;
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinEmployee.kt",
                """
                    package com.acme.entity
                    import io.jmix.core.metamodel.annotation.JmixEntity
                    import jakarta.persistence.*
                    import java.util.UUID
                    @JmixEntity
                    @Entity
                    @Table(name = "KOTLIN_EMPLOYEE")
                    class KotlinEmployee {
                        @Id
                        @Column(name = "ID")
                        var id: UUID? = null
                        @ManyToOne
                        @JoinColumn(name = "DEPARTMENT_ID", referencedColumnName = "ID", nullable = false)
                        var department: Department? = null
                        fun manualLabel(): String = "kotlin-employee"
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/ExclusiveEmployee.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "EXCLUSIVE_EMPLOYEE")
                    public class ExclusiveEmployee {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                        @OneToOne
                        @JoinColumn(name = "DEPARTMENT_ID", unique = true)
                        private Department department;
                        public String manualLabel() { return "exclusive"; }
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinExclusiveEmployee.kt",
                """
                    package com.acme.entity
                    import io.jmix.core.metamodel.annotation.JmixEntity
                    import jakarta.persistence.*
                    import java.util.UUID
                    @JmixEntity
                    @Entity
                    @Table(name = "KOTLIN_EXCLUSIVE_EMPLOYEE")
                    class KotlinExclusiveEmployee {
                        @Id
                        @Column(name = "ID")
                        var id: UUID? = null
                        @OneToOne
                        @JoinColumn(name = "DEPARTMENT_ID", unique = true)
                        var department: Department? = null
                        fun manualLabel(): String = "kotlin-exclusive"
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/UnprovenExclusiveEmployee.java",
                """
                    package com.acme.entity;
                    import io.jmix.core.metamodel.annotation.JmixEntity;
                    import jakarta.persistence.*;
                    @JmixEntity @Entity @Table(name = "UNPROVEN_EXCLUSIVE_EMPLOYEE")
                    public class UnprovenExclusiveEmployee {
                        @Id @Column(name = "ID")
                        private java.util.UUID id;
                        @OneToOne
                        @JoinColumn(name = "DEPARTMENT_ID", unique = true)
                        private Department department;
                    }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog/025-exclusive-employees.xml",
                """
                    <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                        <changeSet id="exclusive-employees" author="legacy">
                            <createTable tableName="EXCLUSIVE_EMPLOYEE">
                                <column name="ID" type="UUID"/>
                                <column name="DEPARTMENT_ID" type="UUID"/>
                            </createTable>
                            <addUniqueConstraint
                                tableName="EXCLUSIVE_EMPLOYEE"
                                constraintName="UQ_EXCLUSIVE_EMPLOYEE_DEPARTMENT"
                                columnNames="DEPARTMENT_ID"/>
                            <createTable tableName="KOTLIN_EXCLUSIVE_EMPLOYEE">
                                <column name="ID" type="UUID"/>
                                <column name="DEPARTMENT_ID" type="UUID"/>
                            </createTable>
                            <createIndex
                                tableName="KOTLIN_EXCLUSIVE_EMPLOYEE"
                                indexName="UX_KOTLIN_EXCLUSIVE_EMPLOYEE_DEPARTMENT"
                                unique="true">
                                <column name="DEPARTMENT_ID"/>
                            </createIndex>
                        </changeSet>
                    </databaseChangeLog>
                """.trimIndent(),
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val store = workspace.stores.single()
        val service = ExistingEntityChangeService.getInstance(project)

        fun model(
            snapshot: SchemaEntitySnapshot,
            newColumn: String,
            targetAssociationType: AssociationType? = null,
            targetUnique: Boolean? = null,
        ): EntityModel {
            val relationAttribute = snapshot.attributes.single { it.name == "department" }
            val relation = requireNotNull(relationAttribute.associationDetails)
            return EntityModel(
                className = snapshot.className,
                packageName = snapshot.qualifiedName.substringBeforeLast('.'),
                sourceLanguage = if (snapshot.sourceLocator.relativePath.endsWith(".kt")) {
                    EntitySourceLanguage.KOTLIN
                } else {
                    EntitySourceLanguage.JAVA
                },
                tableName = snapshot.tableName,
                dataStore = snapshot.storeName,
                generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
                attributes = mutableListOf(
                    AttributeModel(
                        name = relationAttribute.name,
                        type = AttributeType.ASSOCIATION,
                        columnName = relationAttribute.columnName,
                        mandatory = !relationAttribute.nullable,
                        unique = targetUnique ?: relationAttribute.unique,
                        association = AssociationConfig(
                            associationType = targetAssociationType ?: relation.associationType,
                            relatedEntity = relation.relatedEntity,
                            relatedTableName = relation.relatedTableName,
                            relatedIdColumnName = relation.relatedIdColumnName,
                            relatedIdType = relation.relatedIdType,
                            localIdAttributeName = relation.localIdAttributeName,
                            mappedBy = relation.mappedBy,
                            joinColumnName = newColumn,
                            joinTable = relation.joinTable,
                            cascade = relation.cascade.toMutableList(),
                            fetch = relation.fetch,
                            collectionType = relation.collectionType,
                            crossDataStore = relation.crossDataStore,
                            orphanRemoval = relation.orphanRemoval,
                            onDelete = relation.onDelete,
                        ),
                    ),
                ),
            )
        }

        listOf("Employee", "KotlinEmployee").forEach { className ->
            val snapshot = workspace.entities.single { it.className == className }
            val preview = service.previewAttributeAdditions(
                ExistingEntityAttributeAdditionRequest(
                    snapshot.sourceLocator,
                    model(snapshot, "ORG_UNIT_ID"),
                ),
            )
            assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
            assertEquals(2, preview.files.size)
            val source = preview.files.single {
                it.relativePath.endsWith(if (className == "Employee") ".java" else ".kt")
            }.resultContent
            assertTrue(source.contains("name = \"ORG_UNIT_ID\""))
            assertTrue(source.contains("referencedColumnName = \"ID\""))
            assertTrue(source.contains("nullable = false"))
            assertTrue(source.contains("manualLabel"))
            if (className == "Employee") {
                assertTrue(source.contains("foreignKey = @ForeignKey(name = \"FK_EMPLOYEE_DEPARTMENT\")"))
            }
            val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
            assertTrue(
                migration.contains(
                    "oldColumnName=\"DEPARTMENT_ID\" newColumnName=\"ORG_UNIT_ID\"",
                ),
            )
            assertTrue(
                migration.contains(
                    "oldColumnName=\"ORG_UNIT_ID\" newColumnName=\"DEPARTMENT_ID\"",
                ),
            )
            assertTrue(migration.contains("columnName=\"DEPARTMENT_ID\""))
            assertTrue(migration.contains("columnName=\"ORG_UNIT_ID\""))
        }

        listOf("Employee", "KotlinEmployee").forEach { className ->
            val snapshot = workspace.entities.single { it.className == className }
            val preview = service.previewAttributeAdditions(
                ExistingEntityAttributeAdditionRequest(
                    snapshot.sourceLocator,
                    model(
                        snapshot,
                        "DEPARTMENT_ID",
                        AssociationType.ONE_TO_ONE,
                        targetUnique = true,
                    ),
                ),
            )
            assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
            assertEquals(2, preview.files.size)
            val source = preview.files.single {
                it.relativePath.endsWith(if (className == "Employee") ".java" else ".kt")
            }.resultContent
            assertTrue(source.contains("@OneToOne"))
            assertFalse(source.contains("@ManyToOne"))
            assertTrue(source.contains("name = \"DEPARTMENT_ID\""))
            assertTrue(source.contains("referencedColumnName = \"ID\""))
            assertTrue(source.contains("nullable = false"))
            assertTrue(source.contains("unique = true"))
            assertTrue(source.contains("manualLabel"))
            if (className == "Employee") {
                assertTrue(source.contains("foreignKey = @ForeignKey(name = \"FK_EMPLOYEE_DEPARTMENT\")"))
            }
            val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
            val constraint = "UQ_${snapshot.tableName}_DEPARTMENT_ID"
            assertTrue(migration.contains("HAVING COUNT(*) &gt; 1"))
            assertTrue(migration.contains("""<addUniqueConstraint tableName="${snapshot.tableName}""""))
            assertTrue(migration.contains("""constraintName="$constraint""""))
            assertTrue(migration.contains("""<dropUniqueConstraint tableName="${snapshot.tableName}""""))
        }

        listOf(
            "ExclusiveEmployee" to "UQ_EXCLUSIVE_EMPLOYEE_DEPARTMENT",
            "KotlinExclusiveEmployee" to "UX_KOTLIN_EXCLUSIVE_EMPLOYEE_DEPARTMENT",
        ).forEach { (className, backingName) ->
            val snapshot = workspace.entities.single { it.className == className }
            val preview = service.previewAttributeAdditions(
                ExistingEntityAttributeAdditionRequest(
                    snapshot.sourceLocator,
                    model(
                        snapshot,
                        "DEPARTMENT_ID",
                        AssociationType.MANY_TO_ONE,
                        targetUnique = false,
                    ),
                ),
            )
            assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
            assertEquals(2, preview.files.size)
            val source = preview.files.single {
                it.relativePath.endsWith(if (className == "ExclusiveEmployee") ".java" else ".kt")
            }.resultContent
            assertTrue(source.contains("@ManyToOne"))
            assertFalse(source.contains("@OneToOne"))
            assertFalse(source.contains("unique = true"))
            assertTrue(source.contains("manualLabel"))
            val migration = preview.files.single { it.relativePath.endsWith(".xml") }.resultContent
            if (className == "ExclusiveEmployee") {
                assertTrue(migration.contains("""<dropUniqueConstraint tableName="${snapshot.tableName}""""))
                assertTrue(migration.contains("""constraintName="$backingName""""))
                assertTrue(migration.contains("""<addUniqueConstraint tableName="${snapshot.tableName}""""))
            } else {
                assertTrue(migration.contains("""<dropIndex tableName="${snapshot.tableName}""""))
                assertTrue(migration.contains("""indexName="$backingName""""))
                assertTrue(migration.contains("""<createIndex tableName="${snapshot.tableName}""""))
                assertTrue(migration.contains("""unique="true""""))
            }
            assertTrue(migration.contains("<rollback>"))
        }

        val qualifiedWidening = workspace.entities.single { it.className == "ExclusiveEmployee" }
        val qualifiedRejected = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                qualifiedWidening.sourceLocator,
                model(
                    qualifiedWidening,
                    "DEPARTMENT_ID",
                    AssociationType.MANY_TO_ONE,
                    targetUnique = false,
                ).copy(tableSchema = "tenant"),
            ),
        )
        assertFalse(qualifiedRejected.accepted)
        assertTrue(
            qualifiedRejected.issues.any {
                it.code == "JVW-ENTITY-RELATIONSHIP-WIDENING-QUALIFIED-TABLE-UNPROVEN"
            },
            qualifiedRejected.issues.toString(),
        )
        val externalDdlRejected = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                qualifiedWidening.sourceLocator,
                model(
                    qualifiedWidening,
                    "DEPARTMENT_ID",
                    AssociationType.MANY_TO_ONE,
                    targetUnique = false,
                ).copy(
                    ddlGeneration = DdlGenerationConfig(
                        enabled = false,
                        mode = DdlGenerationMode.DISABLED,
                    ),
                ),
            ),
        )
        assertFalse(externalDdlRejected.accepted)
        assertTrue(
            externalDdlRejected.issues.any {
                it.code == "JVW-ENTITY-RELATIONSHIP-WIDENING-DDL-REQUIRED"
            },
            externalDdlRejected.issues.toString(),
        )

        val unprovenWidening = workspace.entities.single { it.className == "UnprovenExclusiveEmployee" }
        val unprovenRejected = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                unprovenWidening.sourceLocator,
                model(
                    unprovenWidening,
                    "DEPARTMENT_ID",
                    AssociationType.MANY_TO_ONE,
                    targetUnique = false,
                ),
            ),
        )
        assertFalse(unprovenRejected.accepted)
        assertTrue(
            unprovenRejected.issues.any {
                it.code == "JVW-ENTITY-RELATIONSHIP-WIDENING-TABLE-UNRESOLVED"
            },
            unprovenRejected.issues.toString(),
        )

        val unsafe = workspace.entities.single { it.className == "UnsafeEmployee" }
        val rejected = service.previewAttributeAdditions(
            ExistingEntityAttributeAdditionRequest(
                unsafe.sourceLocator,
                model(unsafe, "ORG_UNIT_ID"),
            ),
        )
        assertFalse(rejected.accepted)
        assertTrue(
            rejected.issues.any { it.code == "JVW-ENTITY-RELATIONSHIP-JOIN-COLUMN-INFERRED" },
            rejected.issues.toString(),
        )
    }

    fun testUnmappedColumnQuarantineIsDependencyGatedPreconditionedAndReversible() {
        createFixture(includeAll = true)
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog/020-legacy-column.xml",
                """
                    <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                        <changeSet id="legacy-column" author="legacy">
                            <addColumn tableName="LOAN_APP">
                                <column name="LEGACY_NOTES" type="VARCHAR(255)"/>
                                <column name="INDEXED_LEGACY" type="VARCHAR(64)"/>
                            </addColumn>
                            <createIndex tableName="LOAN_APP" indexName="IDX_LOAN_APP_INDEXED_LEGACY">
                                <column name="INDEXED_LEGACY"/>
                            </createIndex>
                        </changeSet>
                    </databaseChangeLog>
                """.trimIndent(),
            )
        }
        val service = SchemaWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val drift = workspace.drifts.single {
            it.kind == SchemaDriftKind.UNMAPPED_COLUMN &&
                it.columnName == "LEGACY_NOTES"
        }
        assertEquals(SchemaDriftSafety.DATA_CHECK_REQUIRED, drift.safety)
        assertEquals(SchemaDriftConfidence.HIGH, drift.confidence)
        val suggestion = requireNotNull(drift.suggestion)
        assertEquals("renameColumn", suggestion.changeType)
        assertEquals("LEGACY_NOTES", suggestion.columnName)
        assertTrue(requireNotNull(suggestion.newColumnName).startsWith("ZZR_"))
        assertTrue(requireNotNull(suggestion.newColumnName).length <= 30)
        val indexedDrift = workspace.drifts.single {
            it.kind == SchemaDriftKind.UNMAPPED_COLUMN &&
                it.columnName == "INDEXED_LEGACY"
        }
        assertEquals(SchemaDriftSafety.REVIEW, indexedDrift.safety)
        assertEquals(null, indexedDrift.suggestion)
        val physicalTable = workspace.physicalSchemas.single()
            .tables.single { it.name == "LOAN_APP" }
        assertEquals(
            listOf("INDEXED_LEGACY"),
            physicalTable.indexes.single { it.name == "IDX_LOAN_APP_INDEXED_LEGACY" }.columns,
        )

        val proposal = service.previewMigration(
            SchemaMigrationChangeRequest(
                storeId = drift.storeId,
                migration = MigrationModel(
                    changelogId = "quarantine-legacy-notes",
                    changes = mutableListOf(
                        ChangeSetModel(
                            id = "quarantine-legacy-notes",
                            preConditions = mutableListOf(
                                org.jmixworkbench.model.PreCondition(
                                    type = org.jmixworkbench.model.PreConditionType.COLUMN_EXISTS,
                                    params = mutableMapOf(
                                        "tableName" to drift.tableName,
                                        "columnName" to requireNotNull(suggestion.columnName),
                                    ),
                                ),
                                org.jmixworkbench.model.PreCondition(
                                    type = org.jmixworkbench.model.PreConditionType.COLUMN_NOT_EXISTS,
                                    params = mutableMapOf(
                                        "tableName" to drift.tableName,
                                        "columnName" to requireNotNull(suggestion.newColumnName),
                                    ),
                                ),
                            ),
                            changes = mutableListOf(
                                DbChange.RenameColumn(
                                    tableName = drift.tableName,
                                    oldColumnName = requireNotNull(suggestion.columnName),
                                    newColumnName = requireNotNull(suggestion.newColumnName),
                                    columnDataType = suggestion.columnType,
                                ),
                            ),
                            rollback = mutableListOf(
                                DbChange.RenameColumn(
                                    tableName = drift.tableName,
                                    oldColumnName = requireNotNull(suggestion.newColumnName),
                                    newColumnName = requireNotNull(suggestion.columnName),
                                    columnDataType = suggestion.columnType,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(proposal.accepted, proposal.issues.joinToString { "${it.code}: ${it.message}" })
        val migration = proposal.files.single().resultContent
        assertTrue(migration.contains("<columnExists tableName=\"LOAN_APP\" columnName=\"LEGACY_NOTES\""))
        assertTrue(migration.contains("<not>"))
        assertTrue(
            migration.contains(
                "oldColumnName=\"LEGACY_NOTES\" newColumnName=\"${suggestion.newColumnName}\"",
            ),
        )
        assertTrue(
            migration.contains(
                "oldColumnName=\"${suggestion.newColumnName}\" newColumnName=\"LEGACY_NOTES\"",
            ),
        )

        WriteAction.run<RuntimeException> {
            val entityFile = requireNotNull(
                root.findFileByRelativePath("src/main/java/com/acme/entity/LoanApp.java"),
            )
            val source = String(entityFile.contentsToByteArray())
            write(
                root,
                "src/main/java/com/acme/entity/LoanApp.java",
                source.replace(
                    "@JmixEntity",
                    "@DdlGeneration(unmappedColumns = {\"LEGACY_NOTES\"})\n@JmixEntity",
                ),
            )
        }
        val protectedWorkspace = service.load(forceRefresh = true)
        val protectedDrift = protectedWorkspace.drifts.single {
            it.kind == SchemaDriftKind.UNMAPPED_COLUMN &&
                it.columnName == "LEGACY_NOTES"
        }
        assertEquals(SchemaDriftSafety.REVIEW, protectedDrift.safety)
        assertEquals(null, protectedDrift.suggestion)
        assertTrue(protectedDrift.message.contains("explicitly protects"))
    }

    private fun addStatusMigration() = MigrationModel(
        changelogId = "loan-status",
        author = "team",
        changes = mutableListOf(
            ChangeSetModel(
                id = "loan-status-1",
                author = "team",
                changes = mutableListOf(
                    DbChange.AddColumn(
                        tableName = "LOAN_APP",
                        columns = mutableListOf(ColumnDef("STATUS", "VARCHAR(32)", nullable = false)),
                    ),
                ),
            ),
        ),
    )

    private fun createFixture(includeAll: Boolean) {
        val root = getOrCreateProjectBaseDir()
        val rootChangelog = if (includeAll) {
            """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <includeAll path="com/acme/liquibase/changelog"/>
            </databaseChangeLog>
            """.trimIndent()
        } else {
            """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <include file="com/acme/liquibase/changelog/010-init.xml"/>
            </databaseChangeLog>
            """.trimIndent()
        }
        val entity = """
            package com.acme.entity;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import jakarta.persistence.*;
            @JmixEntity
            @Entity
            @Table(name = "LOAN_APP")
            public class LoanApp {
                @Id
                private java.util.UUID id;
                @Column(name = "APPLICATION_NO", nullable = false)
                private String applicationNo;
                @Column(name = "LOAN_AMOUNT", nullable = false, precision = 19, scale = 2)
                private java.math.BigDecimal loanAmount;

                public int calculateRisk() {
                    return 42;
                }
            }
        """.trimIndent()
        val initial = """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <changeSet id="existing" author="team">
                    <createTable tableName="LOAN_APP">
                        <column name="ID" type="UUID"/>
                    </createTable>
                </changeSet>
            </databaseChangeLog>
        """.trimIndent()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.firstOrNull()
                ?: ModuleManager.getInstance(project).newModule(
                    "${root.path}/schema-test.iml",
                    ModuleType.EMPTY.id,
                )
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(root)
                rootModel.commit()
            }
            write(root, "src/main/java/com/acme/entity/LoanApp.java", entity)
            write(
                root,
                "src/main/resources/application.properties",
                "main.liquibase.change-log=com/acme/liquibase/changelog.xml\n",
            )
            write(root, "src/main/resources/com/acme/liquibase/changelog.xml", rootChangelog)
            write(root, "src/main/resources/com/acme/liquibase/changelog/010-init.xml", initial)
        }
    }

    private fun databaseProfileRequest(
        store: SchemaDataStoreSnapshot,
        label: String,
    ) = DatabaseEntityImportRequest(
        storeId = store.id,
        moduleId = store.moduleId,
        packageName = "com.acme.entity",
        sourceLanguage = EntitySourceLanguage.KOTLIN,
        selectedTables = listOf(
            DatabaseTableReference("payroll", "public", "ACCOUNT", "TABLE", null),
        ),
        profileId = "account-model",
        profileLabel = label,
    )

    private fun createRelationshipFixture() {
        val root = getOrCreateProjectBaseDir()
        val loanApp = """
            package com.acme.entity;

            import io.jmix.core.DeletePolicy;
            import io.jmix.core.entity.annotation.OnDelete;
            import io.jmix.core.entity.annotation.SystemLevel;
            import io.jmix.core.metamodel.annotation.Composition;
            import io.jmix.core.metamodel.annotation.DependsOnProperties;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import io.jmix.core.metamodel.annotation.JmixProperty;
            import jakarta.persistence.*;
            import java.util.List;
            import java.util.Set;
            import java.util.UUID;

            @JmixEntity
            @Entity(name = "loan_LoanApp")
            @Table(name = "LOAN_LOAN_APP")
            public class LoanApp {
                @Id
                @Column(name = "ID", nullable = false)
                private UUID id;

                @ManyToOne(fetch = FetchType.EAGER, optional = false, cascade = {CascadeType.MERGE})
                @JoinColumn(name = "LOAN_ACCT_ID", referencedColumnName = "ACCT_ID", nullable = false)
                private LoanAcct loanAcct;

                @Composition
                @OnDelete(DeletePolicy.CASCADE)
                @OneToMany(mappedBy = "loanApp", cascade = {CascadeType.ALL}, orphanRemoval = true)
                private List<LoanSchedule> schedules;

                @ManyToMany(fetch = FetchType.LAZY)
                @JoinTable(
                    name = "LOAN_APP_DOCUMENT_LINK",
                    joinColumns = @JoinColumn(name = "APP_ID"),
                    inverseJoinColumns = @JoinColumn(name = "DOC_ID")
                )
                private Set<LoanDocument> documents;

                @SystemLevel
                @Column(name = "FUND_PROFILE_ID")
                private UUID fundProfileId;

                @Transient
                @JmixProperty
                @DependsOnProperties("fundProfileId")
                private FundProfile fundProfile;
            }
        """.trimIndent()
        val loanAcct = """
            package com.acme.entity;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import jakarta.persistence.*;
            @JmixEntity
            @Entity(name = "loan_LoanAcct")
            @Table(name = "LOAN_ACCT")
            public class LoanAcct {
                @Id
                @Column(name = "ACCT_ID", nullable = false)
                private Long id;
            }
        """.trimIndent()
        val schedule = """
            package com.acme.entity;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import jakarta.persistence.*;
            import java.util.UUID;
            @JmixEntity
            @Entity(name = "loan_LoanSchedule")
            @Table(name = "LOAN_SCHEDULE")
            public class LoanSchedule {
                @Id
                private UUID id;
                @ManyToOne(fetch = FetchType.LAZY)
                @JoinColumn(name = "LOAN_APP_ID")
                private LoanApp loanApp;
            }
        """.trimIndent()
        val document = """
            package com.acme.entity;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import jakarta.persistence.*;
            import java.util.UUID;
            @JmixEntity
            @Entity(name = "loan_LoanDocument")
            @Table(name = "LOAN_DOCUMENT")
            public class LoanDocument {
                @Id
                private UUID id;
            }
        """.trimIndent()
        val fundProfile = """
            package com.acme.entity;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import io.jmix.core.metamodel.annotation.Store;
            import jakarta.persistence.*;
            import java.util.UUID;
            @JmixEntity
            @Store(name = "fund")
            @Entity(name = "fund_FundProfile")
            @Table(name = "FUND_PROFILE")
            public class FundProfile {
                @Id
                private UUID id;
            }
        """.trimIndent()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.firstOrNull()
                ?: ModuleManager.getInstance(project).newModule(
                    "${root.path}/relationship-schema-test.iml",
                    ModuleType.EMPTY.id,
                )
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(root)
                rootModel.commit()
            }
            write(root, "src/main/java/com/acme/entity/LoanApp.java", loanApp)
            write(root, "src/main/java/com/acme/entity/LoanAcct.java", loanAcct)
            write(root, "src/main/java/com/acme/entity/LoanSchedule.java", schedule)
            write(root, "src/main/java/com/acme/entity/LoanDocument.java", document)
            write(root, "src/main/java/com/acme/entity/FundProfile.java", fundProfile)
        }
    }

    private fun write(root: com.intellij.openapi.vfs.VirtualFile, path: String, content: String) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            root
        } else {
            requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        }
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}

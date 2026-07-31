package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.jmixworkbench.model.AssociationConfig
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.AttributeModel
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.DdlGenerationConfig
import org.jmixworkbench.model.DdlGenerationMode
import org.jmixworkbench.model.EntityGenerationTarget
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.IdConfig
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EntityAttributePropagationServiceTest : HeavyPlatformTestCase() {
    fun testNewHandwrittenKotlinAttributeAndViewsAreOneAtomicChange() {
        createFixture()
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/kotlin/com/acme/entity/KotlinPayrollRun.kt",
                """
                package com.acme.entity

                import io.jmix.core.metamodel.annotation.JmixEntity
                import jakarta.persistence.Column
                import jakarta.persistence.Entity
                import jakarta.persistence.Id
                import jakarta.persistence.Table
                import java.util.UUID

                @JmixEntity(name = "acme_KotlinPayrollRun")
                @Entity
                @Table(name = "KOTLIN_PAYROLL_RUN")
                open class KotlinPayrollRun {
                    @Id
                    @Column(name = "ID", nullable = false)
                    var id: UUID? = null

                    @Column(name = "RUN_NO", nullable = false, length = 40)
                    var runNo: String? = null

                    fun manualLedgerCode(): String = "PAYROLL"
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/view/payroll/kotlin-payroll-run-detail-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="acme_KotlinPayrollRun.detail">
                    <data>
                        <instance id="payrollRunDc" class="com.acme.entity.KotlinPayrollRun"/>
                    </data>
                    <layout>
                        <formLayout id="payrollRunForm" dataContainer="payrollRunDc"/>
                    </layout>
                </view>
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/view/payroll/kotlin-payroll-run-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="acme_KotlinPayrollRun.list">
                    <data>
                        <collection id="payrollRunsDc" class="com.acme.entity.KotlinPayrollRun"/>
                    </data>
                    <layout>
                        <dataGrid id="payrollRunsGrid" dataContainer="payrollRunsDc"/>
                    </layout>
                </view>
                """.trimIndent(),
            )
        }
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val snapshot = schema.entities.single { it.className == "KotlinPayrollRun" }
        val store = schema.stores.single { it.moduleId == snapshot.moduleId && it.name == snapshot.storeName }
        val desired = EntityModel(
            className = snapshot.className,
            packageName = snapshot.qualifiedName.substringBeforeLast('.'),
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            dataStore = snapshot.storeName,
            generationTarget = EntityGenerationTarget(snapshot.moduleId, store.id),
            entityName = snapshot.entityName,
            tableName = snapshot.tableName,
            id = IdConfig(type = snapshot.idType, columnName = snapshot.idColumnName),
            attributes = mutableListOf(
                AttributeModel(
                    name = "runNo",
                    type = AttributeType.STRING,
                    columnName = "RUN_NO",
                    mandatory = true,
                    length = 40,
                ),
                AttributeModel(
                    name = "grossAmount",
                    type = AttributeType.BIG_DECIMAL,
                    columnName = "GROSS_AMOUNT",
                    mandatory = true,
                    precision = 19,
                    scale = 2,
                ),
            ),
            ddlGeneration = DdlGenerationConfig(
                enabled = false,
                mode = DdlGenerationMode.DISABLED,
            ),
        )
        val request = EntityAttributePropagationInspectionRequest(
            entityQualifiedName = snapshot.qualifiedName,
            entityName = snapshot.entityName,
            className = snapshot.className,
            attributeNames = listOf("grossAmount"),
            entityChange = ExistingEntityAttributeAdditionRequest(snapshot.sourceLocator, desired),
        )
        val service = EntityAttributePropagationService.getInstance(project)
        val inspection = service.inspect(request)
        assertTrue(inspection.accepted, inspection.issues.joinToString { "${it.code}: ${it.message}" })
        val selected = inspection.targets.filter {
            it.supported && it.recommended && !it.securityExpanding
        }
        val preview = service.preview(
            EntityAttributePropagationChangeRequest(request, selected.map { it.id }),
        )
        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val kotlin = preview.files.single { it.relativePath.endsWith("KotlinPayrollRun.kt") }
        assertTrue(kotlin.resultContent.contains("fun manualLedgerCode(): String = \"PAYROLL\""))
        assertTrue(kotlin.resultContent.contains("var grossAmount: BigDecimal? = null"))
        assertTrue(kotlin.resultContent.contains("import java.math.BigDecimal"))
        assertTrue(
            preview.files.single { it.relativePath.endsWith("kotlin-payroll-run-detail-view.xml") }
                .resultContent.contains("""<bigDecimalField property="grossAmount"/>"""),
        )
        assertTrue(
            preview.files.single { it.relativePath.endsWith("kotlin-payroll-run-list-view.xml") }
                .resultContent.contains("""<column property="grossAmount"/>"""),
        )
    }

    fun testNewHandwrittenAttributeAndConnectedSurfacesAreOneAtomicChange() {
        createFixture()
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val entity = schema.entities.single { it.qualifiedName == "com.acme.entity.LoanApp" }
        val store = schema.stores.single { it.moduleId == entity.moduleId && it.name == entity.storeName }
        val desired = EntityModel(
            className = entity.className,
            packageName = entity.qualifiedName.substringBeforeLast('.'),
            dataStore = entity.storeName,
            generationTarget = EntityGenerationTarget(entity.moduleId, store.id),
            entityName = entity.entityName,
            tableName = entity.tableName,
            id = IdConfig(type = entity.idType, columnName = entity.idColumnName),
            attributes = mutableListOf(
                AttributeModel(
                    name = "applicationNo",
                    type = AttributeType.STRING,
                    columnName = "APPLICATION_NO",
                    mandatory = true,
                ),
                AttributeModel(
                    name = "department",
                    type = AttributeType.ASSOCIATION,
                    columnName = "DEPARTMENT_ID",
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = "com.acme.entity.Department",
                        relatedTableName = "DEPARTMENT",
                        relatedIdColumnName = "ID",
                        joinColumnName = "DEPARTMENT_ID",
                    ),
                ),
                AttributeModel(
                    name = "approvedAmount",
                    type = AttributeType.BIG_DECIMAL,
                    columnName = "APPROVED_AMOUNT",
                    mandatory = true,
                    precision = 19,
                    scale = 2,
                ),
            ),
            ddlGeneration = DdlGenerationConfig(
                enabled = false,
                mode = DdlGenerationMode.DISABLED,
            ),
        )
        val entityChange = ExistingEntityAttributeAdditionRequest(entity.sourceLocator, desired)
        val request = EntityAttributePropagationInspectionRequest(
            entityQualifiedName = entity.qualifiedName,
            entityName = entity.entityName,
            className = entity.className,
            attributeNames = listOf("approvedAmount"),
            entityChange = entityChange,
        )
        val service = EntityAttributePropagationService.getInstance(project)
        val inspection = service.inspect(request)

        assertTrue(inspection.accepted, inspection.issues.joinToString { "${it.code}: ${it.message}" })
        val hiddenAddition = service.inspect(
            request.copy(
                entityChange = entityChange.copy(
                    entity = desired.copy(
                        attributes = (
                            desired.attributes + AttributeModel(
                                name = "unreviewedInternalCode",
                                type = AttributeType.STRING,
                                columnName = "UNREVIEWED_INTERNAL_CODE",
                            )
                        ).toMutableList(),
                    ),
                ),
            ),
        )
        assertFalse(hiddenAddition.accepted)
        assertTrue(
            hiddenAddition.issues.any {
                it.message.contains("Unreviewed additions cannot be hidden")
            },
        )
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.VIEW_FORM })
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.VIEW_GRID })
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.MESSAGE_BUNDLE })

        val selected = inspection.targets.filter {
            it.supported && it.recommended && !it.securityExpanding
        }
        val change = EntityAttributePropagationChangeRequest(request, selected.map { it.id })
        val preview = service.preview(change)

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertTrue(preview.label.startsWith("Add and propagate 1 LoanApp attribute"))
        val source = preview.files.single { it.relativePath.endsWith("/LoanApp.java") }
        assertTrue(source.resultContent.contains("protected BigDecimal approvedAmount;"))
        assertTrue(source.resultContent.contains("@Column(name = \"APPROVED_AMOUNT\", nullable = false, precision = 19, scale = 2)"))
        val detail = preview.files.single { it.relativePath.endsWith("loan-app-detail-view.xml") }
        assertTrue(detail.resultContent.contains("""<bigDecimalField property="approvedAmount"/>"""))
        val list = preview.files.single { it.relativePath.endsWith("loan-app-list-view.xml") }
        assertTrue(list.resultContent.contains("""<column property="approvedAmount"/>"""))
        val messages = preview.files.single { it.relativePath.endsWith("entity/messages.properties") }
        assertTrue(messages.resultContent.contains("LoanApp.approvedAmount=Approved Amount"))

        val prepared = service.prepareApply(
            EntityAttributePropagationApplyRequest(change, requireNotNull(preview.planDigest)),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        val sourceAfter = ProjectSourceText.read(
            requireNotNull(
                ProjectFileResolver.getInstance(project)
                    .resolveFile(entity.sourceLocator.relativePath),
            ).file,
        )
        assertTrue(sourceAfter.contains("protected BigDecimal approvedAmount;"))
    }

    fun testConnectedViewsFetchPlansAndMessagesArePlannedAtomicallyWithoutGrantingSecurity() {
        createFixture()
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val entity = schema.entities.single { it.qualifiedName == "com.acme.entity.LoanApp" }
        val request = EntityAttributePropagationInspectionRequest(
            entityQualifiedName = entity.qualifiedName,
            entityName = entity.entityName,
            className = entity.className,
            attributeNames = listOf("applicationNo", "department"),
        )
        val service = EntityAttributePropagationService.getInstance(project)
        val inspection = service.inspect(request)

        assertTrue(inspection.accepted, inspection.issues.joinToString { "${it.code}: ${it.message}" })
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.VIEW_FORM })
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.VIEW_GRID })
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.INLINE_FETCH_PLAN })
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.SHARED_FETCH_PLAN })
        assertTrue(inspection.targets.any { it.kind == EntityAttributePropagationTargetKind.MESSAGE_BUNDLE })
        val security = inspection.targets.single {
            it.kind == EntityAttributePropagationTargetKind.RESOURCE_ROLE
        }
        assertTrue(security.supported)
        assertFalse(security.recommended)
        assertTrue(security.securityExpanding)

        val selected = inspection.targets.filter { it.supported }
        val preview = service.preview(
            EntityAttributePropagationChangeRequest(request, selected.map { it.id }),
        )
        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertNotNull(preview.planDigest)
        assertTrue(preview.files.size >= 4)

        val detail = preview.files.single { it.relativePath.endsWith("loan-app-detail-view.xml") }
        assertTrue(detail.resultContent.contains("""<textField property="applicationNo"/>"""))
        assertTrue(detail.resultContent.contains("""<entityPicker property="department"/>"""))
        assertTrue(detail.resultContent.contains("""<property name="department" fetchPlan="_base"/>"""))
        assertTrue(detail.resultContent.contains("<!-- handwritten layout remains -->"))

        val list = preview.files.single { it.relativePath.endsWith("loan-app-list-view.xml") }
        assertTrue(list.resultContent.contains("""<column property="applicationNo"/>"""))
        assertTrue(list.resultContent.contains("""<column property="department"/>"""))

        val shared = preview.files.single { it.relativePath.endsWith("loan-fetch-plans.xml") }
        assertTrue(shared.resultContent.contains("""<property name="department" fetchPlan="_base"/>"""))
        assertFalse(
            shared.resultContent.contains("""<property name="applicationNo"/>"""),
            "_base already loads local scalar attributes",
        )

        val messages = preview.files.single { it.relativePath.endsWith("entity/messages.properties") }
        assertTrue(messages.resultContent.contains("LoanApp.applicationNo=Application No"))
        assertTrue(messages.resultContent.contains("LoanApp.department=Department"))
        val role = preview.files.single { it.relativePath.endsWith("LoanUserRole.java") }
        assertTrue(role.resultContent.contains(""""applicationNo", "department""""))

        val change = EntityAttributePropagationChangeRequest(request, selected.map { it.id })
        val prepared = service.prepareApply(
            EntityAttributePropagationApplyRequest(change, requireNotNull(preview.planDigest)),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        val after = service.inspect(request)
        assertTrue(
            after.targets.none { it.supported },
            "Applied view, fetch-plan, message, and exact role targets must not be proposed twice: ${after.targets}",
        )

        val stale = service.preview(
            EntityAttributePropagationChangeRequest(
                request,
                listOf("not-a-current-target"),
            ),
        )
        assertFalse(stale.accepted)
        assertTrue(stale.issues.any { it.code == "JVW-PROPAGATION-TARGET-STALE" })
    }

    fun testUnknownEntityAttributeIsRejectedBeforeAnySourcePlan() {
        createFixture()
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val entity = schema.entities.single { it.qualifiedName == "com.acme.entity.LoanApp" }
        val response = EntityAttributePropagationService.getInstance(project).inspect(
            EntityAttributePropagationInspectionRequest(
                entityQualifiedName = entity.qualifiedName,
                entityName = entity.entityName,
                className = entity.className,
                attributeNames = listOf("notPersistedYet"),
            ),
        )
        assertFalse(response.accepted)
        assertTrue(response.issues.any { it.code == "JVW-PROPAGATION-REQUEST-INVALID" })
        assertTrue(response.targets.isEmpty())
    }

    fun testCustomSourceRootsKeepMessagePropagationInTheOwningModuleResourceRoot() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.first()
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                model.addContentEntry(root)
                model.commit()
            }
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                sourceSets {
                    named("main") {
                        java {
                            srcDir("domain-src")
                        }
                        resources.srcDir("runtime-resources")
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "domain-src/com/acme/entity/PayrollRun.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                @JmixEntity(name = "acme_PayrollRun")
                @Entity
                @Table(name = "PAYROLL_RUN")
                public class PayrollRun {
                    @Id
                    private java.util.UUID id;
                    @Column(name = "RUN_NO", nullable = false)
                    private String runNo;
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
                """<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"/>""",
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val entity = workspace.entities.singleOrNull { it.className == "PayrollRun" }
        assertNotNull(
            entity,
            "Custom-root entity was not indexed: entities=${workspace.entities.map { it.qualifiedName }}, " +
                "issues=${workspace.issues}",
        )
        val response = EntityAttributePropagationService.getInstance(project).inspect(
            EntityAttributePropagationInspectionRequest(
                entity.qualifiedName,
                entity.entityName,
                entity.className,
                listOf("runNo"),
            ),
        )
        assertTrue(response.accepted, response.issues.joinToString { "${it.code}: ${it.message}" })
        val messages = response.targets.singleOrNull {
            it.kind == EntityAttributePropagationTargetKind.MESSAGE_BUNDLE
        }
        assertNotNull(
            messages,
            "No message target: targets=${response.targets}, issues=${response.issues}",
        )
        assertTrue(
            messages.relativePath == "runtime-resources/com/acme/entity/messages.properties",
            messages.relativePath,
        )
    }

    private fun createFixture() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.first()
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                model.addContentEntry(root)
                model.commit()
            }
            write(
                root,
                "build.gradle.kts",
                """plugins { id("io.jmix") version "2.8.3" }""",
            )
            write(
                root,
                "src/main/java/com/acme/entity/Department.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                @JmixEntity(name = "acme_Department")
                @Entity
                @Table(name = "DEPARTMENT")
                public class Department {
                    @Id
                    private java.util.UUID id;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/entity/LoanApp.java",
                """
                package com.acme.entity;
                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.*;
                @JmixEntity(name = "acme_LoanApp")
                @Entity
                @Table(name = "LOAN_APP")
                public class LoanApp {
                    @Id
                    private java.util.UUID id;
                    @Column(name = "APPLICATION_NO", nullable = false)
                    private String applicationNo;
                    @ManyToOne(fetch = FetchType.LAZY)
                    @JoinColumn(name = "DEPARTMENT_ID")
                    private Department department;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/application.properties",
                "main.liquibase.change-log=com/acme/liquibase/changelog.xml\n",
            )
            write(
                root,
                "src/main/resources/com/acme/liquibase/changelog.xml",
                """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <includeAll path="com/acme/liquibase/changelog"/>
                </databaseChangeLog>
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/view/loan/loan-app-detail-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="acme_LoanApp.detail">
                    <data>
                        <instance id="loanAppDc" class="com.acme.entity.LoanApp">
                            <fetchPlan extends="_base"/>
                        </instance>
                    </data>
                    <layout>
                        <!-- handwritten layout remains -->
                        <formLayout id="loanAppForm" dataContainer="loanAppDc">
                        </formLayout>
                    </layout>
                </view>
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/view/loan/loan-app-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="acme_LoanApp.list">
                    <data>
                        <collection id="loanAppsDc" class="com.acme.entity.LoanApp"/>
                    </data>
                    <layout>
                        <dataGrid id="loanAppsDataGrid" dataContainer="loanAppsDc">
                        </dataGrid>
                    </layout>
                </view>
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/com/acme/entity/loan-fetch-plans.xml",
                """
                <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                    <fetchPlan class="com.acme.entity.LoanApp"
                               name="loan-summary"
                               extends="_base"/>
                </fetchPlans>
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/security/LoanUserRole.java",
                """
                package com.acme.security;
                import com.acme.entity.LoanApp;
                import io.jmix.security.role.annotation.*;
                @ResourceRole(name = "Loan user", code = "loan-user")
                public interface LoanUserRole {
                    @EntityAttributePolicy(
                        entityClass = LoanApp.class,
                        attributes = {"applicationNo"},
                        action = EntityAttributePolicyAction.VIEW
                    )
                    void loanView();
                }
                """.trimIndent(),
            )
        }
    }

    private fun write(
        root: com.intellij.openapi.vfs.VirtualFile,
        path: String,
        content: String,
    ) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            root
        } else {
            requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        }
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}

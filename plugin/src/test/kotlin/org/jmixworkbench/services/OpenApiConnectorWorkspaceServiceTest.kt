package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jmixworkbench.generator.IntegrationConnectorGenerator
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationOpenApiJmixLayerModel
import org.jmixworkbench.model.IntegrationOpenApiMappingDirection
import org.jmixworkbench.model.IntegrationOpenApiPropertyMapping
import org.jmixworkbench.model.IntegrationOpenApiJmixTargetKind
import org.jmixworkbench.model.IntegrationOpenApiJmixTypeMapping
import java.io.IOException
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenApiConnectorWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testDiscoversBindsGeneratesAndRejectsStaleOpenApiContract() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                dependencies {
                    implementation("io.jmix.flowui:jmix-flowui-starter")
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/payroll/PayrollApplication.java",
                "package com.acme.payroll; public class PayrollApplication {}",
            )
            write(root, "src/main/resources/openapi/hr-provider.yaml", contract("1"))
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )

        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val api = workspace.openApiContracts.single()
        val operation = api.operations.single()
        val binding = requireNotNull(operation.defaultBinding)
        assertTrue(api.valid, api.issues.joinToString())

        val model = IntegrationConnectorModel(
            name = "HR provider",
            destinationId = destination.id,
            packageName = "com.acme.payroll.integration",
            className = "HrProviderConnector",
            beanName = "hrProviderConnector",
            kind = IntegrationConnectorKind.HTTP_CLIENT,
            configurationPrefix = "hr.provider",
            addressProperty = "hr.provider.base-url",
            payloadJavaType = "java.lang.String",
            responseJavaType = "java.lang.String",
            openApiBinding = binding,
        )
        val backendOperation = OpenApiContractService.getInstance(project).resolve(binding).operation
        val forgedDigest = "f".repeat(64)
        val proposal = service.propose(
            model.copy(openApiBaseline = backendOperation.copy(contractSha256 = forgedDigest)),
        )
        val changeSet = requireNotNull(proposal.changeSet) { proposal.issues.joinToString() }
        val generated = requireNotNull(
            changeSet.files.single { it.relativePath.endsWith("HrProviderConnector.java") }.createContent,
        )
        assertContains(generated, "public HrProviderConnector.Employee findEmployee(")
        assertContains(generated, ".path(\"/employees/{employeeId}\")")
        assertContains(generated, "public record Employee(")
        assertFalse(generated.contains("https://hr.example"))
        val encodedMarker = generated.lineSequence()
            .single { it.startsWith(IntegrationConnectorGenerator.markerPrefix()) }
            .removePrefix(IntegrationConnectorGenerator.markerPrefix())
            .trim()
        val persistedModel = String(Base64.getUrlDecoder().decode(encodedMarker), Charsets.UTF_8)
        assertContains(persistedModel, binding.documentSha256)
        assertFalse(forgedDigest in persistedModel)

        val mappedProposal = service.propose(
            model.copy(
                openApiJmixLayer = IntegrationOpenApiJmixLayerModel(
                    enabled = true,
                    dtoPackage = "com.acme.payroll.entity.hr",
                    mapperPackage = "com.acme.payroll.integration.mapper",
                    servicePackage = "com.acme.payroll.service.hr",
                    serviceClassName = "EmployeeDirectoryService",
                    serviceBeanName = "employeeDirectoryService",
                    mappings = listOf(
                        IntegrationOpenApiJmixTypeMapping(
                            schemaId = requireNotNull(operation.responseSchemaId),
                            targetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
                            generatedClassName = "ExternalEmployee",
                            idProperty = "id",
                        ),
                    ),
                ),
            ),
        )
        val mappedChanges = requireNotNull(mappedProposal.changeSet) {
            mappedProposal.issues.joinToString()
        }.files
        assertTrue(mappedChanges.any { it.relativePath.endsWith("/ExternalEmployee.java") })
        val mapper = requireNotNull(
            mappedChanges.single { it.relativePath.endsWith("/EmployeeDirectoryMapper.java") }.createContent,
        )
        val applicationService = requireNotNull(
            mappedChanges.single { it.relativePath.endsWith("/EmployeeDirectoryService.java") }.createContent,
        )
        assertContains(mapper, "metadata.create(com.acme.payroll.entity.hr.ExternalEmployee.class)")
        assertContains(mapper, "entityStates.setNew(target, false)")
        assertContains(applicationService, "public com.acme.payroll.entity.hr.ExternalEmployee findEmployee(")
        assertContains(applicationService, "connector.findEmployee(employeeId)")

        WriteAction.run<RuntimeException> {
            write(root, "src/main/resources/openapi/hr-provider.yaml", contract("2"))
        }
        val stale = service.propose(model)
        assertTrue(stale.changeSet == null)
        assertTrue(stale.issues.any { it.code == "JVW-INTEGRATION-OPENAPI-CONTRACT-INVALID" })
        assertContains(stale.issues.single().message, "changed")

        val current = OpenApiContractService.getInstance(project).resolveCurrent(binding)
        assertEquals("2", current.operation.apiVersion)
        assertTrue(current.operation.contractSha256 != binding.documentSha256)
        assertEquals("findEmployee", current.operation.operationId)
        assertEquals("200", current.operation.responseStatus)
    }

    fun testJmixLayerCreatesReopensUpdatesRemovesAndProtectsEveryOwnedFile() {
        val root = prepareProject(contract("1"))
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val operation = workspace.openApiContracts.single().operations.single()
        val responseSchemaId = requireNotNull(operation.responseSchemaId)
        val base = connector(
            destination.id,
            requireNotNull(operation.defaultBinding),
        )
        val layer = IntegrationOpenApiJmixLayerModel(
            enabled = true,
            dtoPackage = "com.acme.payroll.entity.hr",
            mapperPackage = "com.acme.payroll.integration.mapper",
            servicePackage = "com.acme.payroll.service.hr",
            serviceClassName = "EmployeeDirectoryService",
            serviceBeanName = "employeeDirectoryService",
            mappings = listOf(
                IntegrationOpenApiJmixTypeMapping(
                    schemaId = responseSchemaId,
                    targetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
                    generatedClassName = "ExternalEmployee",
                    idProperty = "id",
                    instanceNameProperty = "displayName",
                ),
            ),
        )
        val model = base.copy(openApiJmixLayer = layer)

        val createdPreview = service.preview(model)
        assertTrue(createdPreview.accepted, createdPreview.issues.joinToString { it.message })
        assertEquals(5, createdPreview.files.size)
        assertTrue(createdPreview.files.all { it.mode.name == "CREATE" })
        apply(service, model, createdPreview)

        val createdPaths = createdPreview.files.mapTo(linkedSetOf()) { it.relativePath }
        assertTrue(createdPaths.all { root.findFileByRelativePath(it) != null })
        ApplicationGraphService.getInstance(project).invalidate()
        val discovered = service.load(forceRefresh = true).existingDocuments.single()
        assertTrue(discovered.editable, discovered.issue)
        assertEquals(layer.serviceClassName, discovered.model.openApiJmixLayer?.serviceClassName)

        val updatedLayer = requireNotNull(discovered.model.openApiJmixLayer).copy(
            mappings = requireNotNull(discovered.model.openApiJmixLayer).mappings.map {
                if (it.schemaId == responseSchemaId) it.copy(instanceNameProperty = "id") else it
            },
        )
        val updated = discovered.model.copy(openApiJmixLayer = updatedLayer)
        val updatePreview = service.preview(updated)
        assertTrue(updatePreview.accepted, updatePreview.issues.joinToString { it.message })
        assertEquals(createdPaths, updatePreview.files.mapTo(linkedSetOf()) { it.relativePath })
        assertTrue(updatePreview.files.all { it.mode.name == "MODIFY" })
        apply(service, updated, updatePreview)
        val dtoPath = updatePreview.files.single { it.relativePath.endsWith("/ExternalEmployee.java") }.relativePath
        assertContains(
            read(requireNotNull(root.findFileByRelativePath(dtoPath))),
            "@InstanceName\n    @JmixProperty(mandatory = true)\n    private java.lang.String id;",
        )

        ApplicationGraphService.getInstance(project).invalidate()
        val updatedDocument = service.load(forceRefresh = true).existingDocuments.single()
        assertTrue(updatedDocument.editable, updatedDocument.issue)
        val removed = updatedDocument.model.copy(openApiJmixLayer = null)
        val removePreview = service.preview(removed)
        assertTrue(removePreview.accepted, removePreview.issues.joinToString { it.message })
        assertEquals(2, removePreview.files.count { it.mode.name == "MODIFY" })
        assertEquals(3, removePreview.files.count { it.mode.name == "DELETE" })
        apply(service, removed, removePreview)
        removePreview.files.filter { it.mode.name == "DELETE" }.forEach {
            assertNull(root.findFileByRelativePath(it.relativePath), "Supplemental source was not deleted: ${it.relativePath}")
        }
        removePreview.files.filter { it.mode.name == "MODIFY" }.forEach {
            assertNotNull(root.findFileByRelativePath(it.relativePath), "Connector source was unexpectedly deleted")
        }

        ApplicationGraphService.getInstance(project).invalidate()
        val transportOnly = service.load(forceRefresh = true).existingDocuments.single()
        assertTrue(transportOnly.editable, transportOnly.issue)
        assertNull(transportOnly.model.openApiJmixLayer)

        val recreatePreview = service.preview(transportOnly.model.copy(openApiJmixLayer = layer))
        assertTrue(recreatePreview.accepted, recreatePreview.issues.joinToString { it.message })
        apply(service, transportOnly.model.copy(openApiJmixLayer = layer), recreatePreview)
        ApplicationGraphService.getInstance(project).invalidate()
        val recreated = service.load(forceRefresh = true).existingDocuments.single()
        assertTrue(recreated.editable, recreated.issue)
        val mapperPath = recreatePreview.files.single {
            it.relativePath.endsWith("/EmployeeDirectoryMapper.java")
        }.relativePath
        WriteAction.run<RuntimeException> {
            val mapper = requireNotNull(root.findFileByRelativePath(mapperPath))
            VfsUtil.saveText(mapper, read(mapper) + "\n// payroll-team customization\n")
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val protected = service.load(forceRefresh = true).existingDocuments.single()
        assertFalse(protected.editable)
        assertContains(requireNotNull(protected.issue), "Manual Java")
        val rejected = service.preview(protected.model)
        assertFalse(rejected.accepted)
        assertTrue(rejected.issues.any { it.code == "JVW-INTEGRATION-SOURCE-NOT-OWNED" })
        assertContains(read(requireNotNull(root.findFileByRelativePath(mapperPath))), "payroll-team customization")
    }

    fun testExistingEntityMappingRejectsStaleBindingAndUnsafePropertyContracts() {
        val root = prepareProject(contract("1"))
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/payroll/entity/Employee.java",
                """
                package com.acme.payroll.entity;

                import io.jmix.core.metamodel.annotation.JmixEntity;

                @JmixEntity
                public class Employee {
                    private String id;
                    private String displayName;

                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }
                    public String getDisplayName() { return displayName; }
                    public void setDisplayName(String displayName) { this.displayName = displayName; }
                }
                """.trimIndent(),
            )
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val operation = workspace.openApiContracts.single().operations.single()
        val responseSchemaId = requireNotNull(operation.responseSchemaId)
        val entity = workspace.entities.single { it.qualifiedName == "com.acme.payroll.entity.Employee" }
        val binding = org.jmixworkbench.model.IntegrationOpenApiExistingEntityBinding(
            artifactId = entity.artifactId,
            qualifiedName = entity.qualifiedName,
            revisionFingerprint = entity.sourceLocator.revisionFingerprint,
        )
        val model = connector(destination.id, requireNotNull(operation.defaultBinding)).copy(
            openApiJmixLayer = IntegrationOpenApiJmixLayerModel(
                enabled = true,
                dtoPackage = "com.acme.payroll.entity.hr",
                mapperPackage = "com.acme.payroll.integration.mapper",
                servicePackage = "com.acme.payroll.service.hr",
                serviceClassName = "EmployeeDirectoryService",
                serviceBeanName = "employeeDirectoryService",
                mappings = listOf(
                    IntegrationOpenApiJmixTypeMapping(
                        schemaId = responseSchemaId,
                        targetKind = IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY,
                        existingEntity = binding,
                        properties = listOf(
                            IntegrationOpenApiPropertyMapping("id", "id", IntegrationOpenApiMappingDirection.INBOUND),
                            IntegrationOpenApiPropertyMapping("displayName", "displayName", IntegrationOpenApiMappingDirection.BIDIRECTIONAL),
                        ),
                    ),
                ),
            ),
        )
        val accepted = service.preview(model)
        assertTrue(accepted.accepted, accepted.issues.joinToString { it.message })
        assertTrue(accepted.files.none { it.relativePath.endsWith("/Employee.java") })

        val unsafe = model.copy(
            openApiJmixLayer = requireNotNull(model.openApiJmixLayer).copy(
                mappings = model.openApiJmixLayer.mappings.map {
                    it.copy(properties = it.properties + IntegrationOpenApiPropertyMapping("displayName", "missing"))
                },
            ),
        )
        val unsafePreview = service.preview(unsafe)
        assertFalse(unsafePreview.accepted)
        assertTrue(unsafePreview.issues.any { "no property 'missing'" in it.message })

        WriteAction.run<RuntimeException> {
            val source = requireNotNull(root.findFileByRelativePath("src/main/java/com/acme/payroll/entity/Employee.java"))
            VfsUtil.saveText(source, read(source).replace("private String displayName;", "private String displayName;\n    private String department;"))
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val stale = service.preview(model)
        assertFalse(stale.accepted)
        assertTrue(stale.issues.any { "changed after selection" in it.message })
    }

    fun testJmixLayerPartialWriteFailureRestoresEveryFileAndCreatedDirectory() {
        val root = prepareProject(contract("1"))
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val operation = workspace.openApiContracts.single().operations.single()
        val model = connector(destination.id, requireNotNull(operation.defaultBinding)).copy(
            openApiJmixLayer = IntegrationOpenApiJmixLayerModel(
                enabled = true,
                dtoPackage = "com.acme.payroll.entity.hr",
                mapperPackage = "com.acme.payroll.integration.mapper",
                servicePackage = "com.acme.payroll.service.hr",
                serviceClassName = "EmployeeDirectoryService",
                serviceBeanName = "employeeDirectoryService",
                mappings = listOf(
                    IntegrationOpenApiJmixTypeMapping(
                        schemaId = requireNotNull(operation.responseSchemaId),
                        generatedClassName = "ExternalEmployee",
                        idProperty = "id",
                    ),
                ),
            ),
        )
        val preview = service.preview(model)
        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val prepared = service.prepare(
            IntegrationConnectorApplyRequest(model, requireNotNull(preview.planDigest)),
        )
        val result = WorkspaceChangeService.getInstance(project).applyPrepared(
            prepared,
            WorkspaceMutationProbe { event ->
                if (event.phase == WorkspaceMutationPhase.AFTER_FILE_MUTATION && event.fileIndex == 2) {
                    throw IOException("Injected connector bundle failure")
                }
            },
        )

        assertFalse(result.success)
        assertTrue(result.issues.any { it.code == "JVW-CHANGE-APPLY-FAILED" })
        preview.files.forEach {
            assertNull(root.findFileByRelativePath(it.relativePath), "Partial file survived rollback: ${it.relativePath}")
        }
        assertNull(root.findFileByRelativePath("src/main/java/com/acme/payroll/entity/hr"))
        assertNull(root.findFileByRelativePath("src/main/java/com/acme/payroll/integration"))
        assertNull(root.findFileByRelativePath("src/main/java/com/acme/payroll/service"))
        assertFalse(WorkspaceHistoryService.getInstance(project).snapshot().canUndo)
    }

    fun testChangedContractRequiresRevisionBoundSemanticApproval() {
        val root = prepareProject(contract("1"))
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val initialWorkspace = service.load(forceRefresh = true)
        val destination = requireNotNull(initialWorkspace.destinations.firstOrNull())
        val operation = initialWorkspace.openApiContracts.single().operations.single()
        val initial = connector(destination.id, requireNotNull(operation.defaultBinding))
        val createPreview = service.preview(initial)
        assertTrue(createPreview.accepted, createPreview.issues.joinToString { it.message })
        apply(service, initial, createPreview)

        WriteAction.run<RuntimeException> {
            write(root, "src/main/resources/openapi/hr-provider.yaml", contract("2", optionalParameter = true))
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val changed = service.load(forceRefresh = true).existingDocuments.single()
        assertFalse(changed.editable)
        assertContains(requireNotNull(changed.issue), "contract changed", ignoreCase = true)
        val evolution = requireNotNull(changed.openApiEvolution)
        assertEquals(OpenApiEvolutionImpact.COMPATIBLE, evolution.report.wireImpact)
        assertEquals(OpenApiEvolutionImpact.BREAKING, evolution.report.sourceImpact)
        assertTrue(evolution.report.changes.any { it.code == "OPENAPI_PARAMETER_ADDED" })

        val candidate = changed.model.copy(openApiBinding = evolution.candidateBinding)
        val unapproved = service.preview(candidate)
        assertFalse(unapproved.accepted)
        assertTrue(unapproved.issues.any {
            it.code == "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-REQUIRED"
        })

        val review = service.openApiEvolutionReview(candidate)
        assertEquals(evolution.report.reportDigest, review.report.reportDigest)
        val approval = service.issueOpenApiEvolutionApproval(candidate)
        val approved = candidate.copy(openApiEvolutionCapability = approval.capability)

        val tampered = service.preview(approved.copy(description = "Changed after approval"))
        assertFalse(tampered.accepted)
        assertTrue(tampered.issues.any {
            it.code == "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-SCOPE-MISMATCH"
        })

        val updatePreview = service.preview(approved)
        assertTrue(updatePreview.accepted, updatePreview.issues.joinToString { it.message })
        assertTrue(updatePreview.files.none { approval.capability in it.resultContent })
        apply(service, approved, updatePreview)
        ApplicationGraphService.getInstance(project).invalidate()
        val reopened = service.load(forceRefresh = true).existingDocuments.single()
        assertTrue(reopened.editable, reopened.issue)
        assertNull(reopened.openApiEvolution)
        assertEquals(evolution.candidateBinding.documentSha256, reopened.model.openApiBaseline?.contractSha256)
    }

    fun testRenamedSchemaCanBeExplicitlyRemappedAndApprovedEndToEnd() {
        val root = prepareProject(contract("1"))
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val initialWorkspace = service.load(forceRefresh = true)
        val destination = requireNotNull(initialWorkspace.destinations.firstOrNull())
        val operation = initialWorkspace.openApiContracts.single().operations.single()
        val responseSchema = requireNotNull(operation.responseSchemaId)
        val initial = connector(destination.id, requireNotNull(operation.defaultBinding)).copy(
            openApiJmixLayer = IntegrationOpenApiJmixLayerModel(
                enabled = true,
                dtoPackage = "com.acme.payroll.entity.integration",
                mapperPackage = "com.acme.payroll.integration.mapper",
                servicePackage = "com.acme.payroll.service.integration",
                serviceClassName = "EmployeeDirectoryService",
                serviceBeanName = "employeeDirectoryService",
                mappings = listOf(
                    IntegrationOpenApiJmixTypeMapping(
                        schemaId = responseSchema,
                        generatedClassName = "ExternalEmployee",
                        idProperty = "externalId",
                        instanceNameProperty = "name",
                        properties = listOf(
                            IntegrationOpenApiPropertyMapping("id", "externalId"),
                            IntegrationOpenApiPropertyMapping("displayName", "name"),
                        ),
                    ),
                ),
            ),
        )
        val createPreview = service.preview(initial)
        assertTrue(createPreview.accepted, createPreview.issues.joinToString { it.message })
        apply(service, initial, createPreview)

        WriteAction.run<RuntimeException> {
            write(root, "src/main/resources/openapi/hr-provider.yaml", renamedContract("2"))
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val changed = service.load(forceRefresh = true).existingDocuments.single()
        val evolution = requireNotNull(changed.openApiEvolution)
        val plan = evolution.remapPlans.single()
        assertEquals("Employee", plan.previousJavaName)
        assertEquals("ExternalEmployee", plan.targetLabel)
        val option = plan.options.single { it.candidateJavaName == "WorkerProfile" }
        assertEquals(OpenApiRemapConfidence.REVIEW, option.confidence)
        assertTrue(option.propertyCandidates.any {
            it.candidateSchemaProperty == "workerId" && it.previousEntityProperty == "externalId"
        })
        assertTrue(option.propertyCandidates.any {
            it.candidateSchemaProperty == "displayLabel" && it.previousEntityProperty == "name"
        })

        val previousLayer = requireNotNull(changed.model.openApiJmixLayer)
        val explicitlyRemapped = changed.model.copy(
            openApiBinding = evolution.candidateBinding,
            openApiJmixLayer = previousLayer.copy(
                mappings = listOf(
                    requireNotNull(previousLayer.mappings.singleOrNull()).copy(
                        schemaId = option.candidateSchemaId,
                        idProperty = "externalId",
                        instanceNameProperty = "name",
                        properties = listOf(
                            IntegrationOpenApiPropertyMapping("workerId", "externalId"),
                            IntegrationOpenApiPropertyMapping("displayLabel", "name"),
                        ),
                    ),
                ),
            ),
        )
        val approval = service.issueOpenApiEvolutionApproval(explicitlyRemapped)
        val nativeReview = service.openApiEvolutionReview(explicitlyRemapped)
        assertEquals(1, nativeReview.mappingDecisionCount)
        assertTrue(nativeReview.mappingDecisionSummaries.single().contains("workerId→externalId"))
        val approved = explicitlyRemapped.copy(openApiEvolutionCapability = approval.capability)
        val updatePreview = service.preview(approved)
        assertTrue(updatePreview.accepted, updatePreview.issues.joinToString { it.message })
        assertTrue(updatePreview.files.any { "class ExternalEmployee" in it.resultContent })
        assertTrue(updatePreview.files.any { "setExternalId(source.workerId())" in it.resultContent })
        assertTrue(updatePreview.files.any { "setName(source.displayLabel())" in it.resultContent })
    }

    private fun prepareProject(openApiContract: String): VirtualFile {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                dependencies { implementation("io.jmix.flowui:jmix-flowui-starter") }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/payroll/PayrollApplication.java",
                "package com.acme.payroll; public class PayrollApplication {}",
            )
            write(root, "src/main/resources/openapi/hr-provider.yaml", openApiContract)
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )
        return root
    }

    private fun connector(
        destinationId: String,
        binding: org.jmixworkbench.model.IntegrationOpenApiBinding,
    ) = IntegrationConnectorModel(
        name = "HR provider",
        destinationId = destinationId,
        packageName = "com.acme.payroll.integration",
        className = "HrProviderConnector",
        beanName = "hrProviderConnector",
        kind = IntegrationConnectorKind.HTTP_CLIENT,
        configurationPrefix = "hr.provider",
        addressProperty = "hr.provider.base-url",
        payloadJavaType = "java.lang.String",
        responseJavaType = "java.lang.String",
        openApiBinding = binding,
    )

    private fun apply(
        service: IntegrationConnectorWorkspaceService,
        model: IntegrationConnectorModel,
        preview: WorkspaceChangePreviewResponse,
    ) {
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepare(
                IntegrationConnectorApplyRequest(model, requireNotNull(preview.planDigest)),
            ),
        )
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
    }

    private fun contract(version: String, optionalParameter: Boolean = false): String {
        val source = """
        openapi: 3.0.3
        info:
          title: HR Provider
          version: "$version"
        paths:
          /employees/{employeeId}:
            get:
              operationId: findEmployee
              parameters:
                - name: employeeId
                  in: path
                  required: true
                  schema: { type: string }
                # OPTIONAL_PARAMETER
              responses:
                "200":
                  description: employee
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/Employee'
        components:
          schemas:
            Employee:
              type: object
              required: [id, displayName]
              properties:
                id: { type: string }
                displayName: { type: string }
        """.trimIndent()
        val parameter = if (optionalParameter) {
            listOf(
                "        - name: locale",
                "          in: query",
                "          required: false",
                "          schema: { type: string }",
            ).joinToString("\n")
        } else {
            ""
        }
        return source.replace("        # OPTIONAL_PARAMETER", parameter)
    }

    private fun renamedContract(version: String): String = """
        openapi: 3.0.3
        info:
          title: HR Provider
          version: "$version"
        paths:
          /employees/{employeeId}:
            get:
              operationId: findEmployee
              parameters:
                - name: employeeId
                  in: path
                  required: true
                  schema: { type: string }
              responses:
                "200":
                  description: employee
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/WorkerProfile'
        components:
          schemas:
            WorkerProfile:
              type: object
              required: [workerId, displayLabel]
              properties:
                workerId: { type: string }
                displayLabel: { type: string }
        """.trimIndent()

    private fun write(root: VirtualFile, path: String, content: String) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            root
        } else {
            requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        }
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }

    private fun read(file: VirtualFile): String =
        String(file.contentsToByteArray(false), file.charset)
}

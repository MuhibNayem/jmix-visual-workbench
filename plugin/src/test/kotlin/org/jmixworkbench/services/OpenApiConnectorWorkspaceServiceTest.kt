package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import kotlin.test.assertContains
import kotlin.test.assertFalse
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
        val proposal = service.propose(model)
        val changeSet = requireNotNull(proposal.changeSet) { proposal.issues.joinToString() }
        val generated = requireNotNull(
            changeSet.files.single { it.relativePath.endsWith("HrProviderConnector.java") }.createContent,
        )
        assertContains(generated, "public HrProviderConnector.Employee findEmployee(")
        assertContains(generated, ".path(\"/employees/{employeeId}\")")
        assertContains(generated, "public record Employee(")
        assertFalse(generated.contains("https://hr.example"))

        WriteAction.run<RuntimeException> {
            write(root, "src/main/resources/openapi/hr-provider.yaml", contract("2"))
        }
        val stale = service.propose(model)
        assertTrue(stale.changeSet == null)
        assertTrue(stale.issues.any { it.code == "JVW-INTEGRATION-OPENAPI-CONTRACT-INVALID" })
        assertContains(stale.issues.single().message, "changed")
    }

    private fun contract(version: String) = """
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

    private fun write(root: VirtualFile, path: String, content: String) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            root
        } else {
            requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        }
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}

package org.jmixworkbench.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson

class JmixEnvironmentConfigurationIntegrationTest : BasePlatformTestCase() {
    fun testInventoryRedactsSecretsAndResolvesProfileGroupsWithoutClaimingRuntimeProof() {
        val applicationContent =
            """
            spring.config.import=optional:file:.env[.properties]
            spring.profiles.active=${'$'}{SPRING_PROFILES_ACTIVE:local}
            spring.profiles.group.production=prod,secure
            main.datasource.password=${'$'}{BRAC_DATABASE_PIN}
            """.trimIndent()
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            applicationContent,
        )
        myFixture.addFileToProject(
            "src/main/resources/application-prod.properties",
            "server.port=8080\n",
        )
        myFixture.addFileToProject(
            "src/main/resources/application-secure.properties",
            "server.servlet.context-path=/payroll\n",
        )
        val environmentContent =
            "SPRING_PROFILES_ACTIVE=production\n" +
                "BRAC_DATABASE_PIN=must-never-cross-jcef\n" +
                "PAYROLL_API_URL=https://sandbox.example/api\n"
        myFixture.addFileToProject(
            ".env",
            environmentContent,
        )
        val launchContent =
            """
            <component name="ProjectRunConfigurationManager">
              <configuration name="Payroll">
                <envs>
                  <env name="SPRING_PROFILES_ACTIVE" value="production"/>
                </envs>
                <option name="envFilePaths" value="${'$'}PROJECT_DIR${'$'}/.env"/>
              </configuration>
            </component>
            """.trimIndent()
        myFixture.addFileToProject(
            ".run/Payroll.run.xml",
            launchContent,
        )

        val service = JmixEnvironmentConfigurationService.getInstance(project)
        val workspace = service.inspect()

        val environment = workspace.files.single()
        assertTrue(environment.relativePath.endsWith("/.env") || environment.relativePath == ".env")
        assertTrue(environment.existing)
        assertEquals(3, environment.variables.size)
        assertEquals(
            "••••••••",
            environment.variables.single { it.name == "BRAC_DATABASE_PIN" }.displayValue,
        )
        assertTrue(environment.variables.single { it.name == "BRAC_DATABASE_PIN" }.secret)
        assertFalse(workspace.toString().contains("must-never-cross-jcef"))
        assertFalse(environment.locator?.revisionFingerprint == CanonicalDiscoveryJson.sha256(environmentContent))
        assertFalse(
            environment.importedBy.single().profileLocator.revisionFingerprint ==
                CanonicalDiscoveryJson.sha256(applicationContent),
        )
        val activation = workspace.activations.single()
        assertEquals(JmixProfileActivationSource.IMPORTED_ENV, activation.source)
        assertEquals(listOf("production"), activation.declaredProfiles)
        assertEquals(listOf("production", "prod", "secure"), activation.expandedProfiles)
        assertTrue(activation.missingProfiles.isEmpty())
        assertFalse(activation.runtimeProven)
        val launch = workspace.launchConfigurations.single()
        assertTrue(launch.activeProfiles.contains("production"))
        assertFalse(launch.runtimeProven)
        assertFalse(launch.revisionFingerprint == CanonicalDiscoveryJson.sha256(launchContent))

        val environmentNavigation = service.prepareNavigation(
            JmixEnvironmentNavigationRequest(
                relativePath = environment.relativePath,
                revisionFingerprint = requireNotNull(environment.locator).revisionFingerprint,
            ),
        )
        assertTrue(environmentNavigation.message, environmentNavigation.success)
        val profileNavigation = service.prepareNavigation(
            JmixEnvironmentNavigationRequest(
                relativePath = environment.importedBy.single().profileLocator.relativePath,
                revisionFingerprint =
                    environment.importedBy.single().profileLocator.revisionFingerprint,
            ),
        )
        assertTrue(profileNavigation.message, profileNavigation.success)
        val forgedNavigation = service.prepareNavigation(
            JmixEnvironmentNavigationRequest(
                relativePath = environment.relativePath,
                revisionFingerprint = "0".repeat(64),
            ),
        )
        assertFalse(forgedNavigation.success)
        assertEquals("JVW-ENV-NAVIGATION-TOKEN-INVALID", forgedNavigation.errorCode)
    }

    fun testNonSecretChangeUsesFocusedPreviewAndExactUndoWhileSecretChangeIsDenied() {
        val original =
            "PAYROLL_API_URL=https://old.example\r\n" +
                "BRAC_API_TOKEN=secret-stays-backend\r\n"
        val source = myFixture.addFileToProject(".env", original).virtualFile
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "spring.config.import=optional:file:.env[.properties]\n" +
                "payroll.url=\${PAYROLL_API_URL}\n" +
                "payroll.token=\${BRAC_API_TOKEN}\n",
        )
        val service = JmixEnvironmentConfigurationService.getInstance(project)
        val workspace = service.inspect()
        val file = workspace.files.single()
        assertFalse(
            CanonicalDiscoveryJson.sha256(original) == file.locator?.revisionFingerprint,
        )
        val change = JmixEnvironmentChangeRequest(
            workspaceDigest = workspace.snapshotDigest,
            relativePath = file.relativePath,
            locator = file.locator,
            variableName = "PAYROLL_API_URL",
            mode = JmixEnvironmentChangeMode.SET,
            value = "https://new.example/payroll",
        )

        val preview = service.previewChange(change)

        assertTrue(preview.issues.joinToString { it.message }, preview.accepted)
        val serializedPreview = preview.files.joinToString {
            "${it.originalContent}\n${it.resultContent}"
        }
        assertFalse(serializedPreview.contains("secret-stays-backend"))
        assertFalse(serializedPreview.contains("BRAC_API_TOKEN"))
        assertTrue(serializedPreview.contains("PAYROLL_API_URL"))
        assertNull(preview.files.single().beforeFingerprint)
        assertEquals("••••••••", preview.files.single().afterFingerprint)
        assertFalse(preview.planDigest == CanonicalDiscoveryJson.sha256(original))
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepareChange(
                JmixEnvironmentChangeApplyRequest(change, requireNotNull(preview.planDigest)),
            ),
        )
        assertTrue(applied.issues.joinToString { it.message }, applied.success)
        assertEquals(
            "PAYROLL_API_URL=https://new.example/payroll\r\n" +
                "BRAC_API_TOKEN=secret-stays-backend\r\n",
            ProjectSourceText.read(source),
        )
        val rawUndo = WorkspaceHistoryService.getInstance(project).undo()
        val undone = service.browserSafeHistoryResponse(rawUndo)
        assertTrue(undone.issues.joinToString { it.message }, undone.success)
        assertFalse(
            undone.revisions[file.relativePath] == CanonicalDiscoveryJson.sha256(original),
        )
        assertEquals(original, ProjectSourceText.read(source))

        val refreshed = service.inspect()
        val secret = service.previewChange(
            JmixEnvironmentChangeRequest(
                workspaceDigest = refreshed.snapshotDigest,
                relativePath = refreshed.files.single().relativePath,
                locator = refreshed.files.single().locator,
                variableName = "BRAC_API_TOKEN",
                mode = JmixEnvironmentChangeMode.SET,
                value = "browser-supplied-secret",
            ),
        )
        assertFalse(secret.accepted)
        assertEquals("JVW-ENV-SECRET-NATIVE-REQUIRED", secret.issues.single().code)
        assertFalse(ProjectSourceText.read(source).contains("browser-supplied-secret"))
    }

    fun testConnectsMissingEnvironmentImportThenCreatesFileWithFirstVariable() {
        val profile = myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "# preserve\r\nserver.port=8080\r\n",
        ).virtualFile
        val service = JmixEnvironmentConfigurationService.getInstance(project)
        val workspace = service.inspect()
        val candidate = workspace.connectionCandidates.single()
        assertFalse(
            candidate.profileLocator.revisionFingerprint ==
                CanonicalDiscoveryJson.sha256(ProjectSourceText.read(profile)),
        )
        val connection = JmixEnvironmentConnectionRequest(
            workspaceDigest = workspace.snapshotDigest,
            profileLocator = candidate.profileLocator,
            environmentFile = ".env",
        )

        val preview = service.previewConnection(connection)

        assertTrue(preview.issues.joinToString { it.message }, preview.accepted)
        assertNull(preview.files.single().originalContent)
        assertNull(preview.files.single().beforeFingerprint)
        assertEquals("••••••••", preview.files.single().afterFingerprint)
        assertFalse(preview.files.single().resultContent.contains("server.port"))
        val connected = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepareConnection(
                JmixEnvironmentConnectionApplyRequest(
                    connection,
                    requireNotNull(preview.planDigest),
                ),
            ),
        )
        assertTrue(connected.issues.joinToString { it.message }, connected.success)
        assertEquals(
            "# preserve\r\nserver.port=8080\r\n" +
                "spring.config.import=optional:file:.env[.properties]\r\n",
            ProjectSourceText.read(profile),
        )

        val connectedWorkspace = service.inspect()
        val missingFile = connectedWorkspace.files.single()
        assertFalse(missingFile.existing)
        val create = JmixEnvironmentChangeRequest(
            workspaceDigest = connectedWorkspace.snapshotDigest,
            relativePath = missingFile.relativePath,
            locator = null,
            variableName = "PAYROLL_API_URL",
            mode = JmixEnvironmentChangeMode.SET,
            value = "https://sandbox.example",
        )
        val createPreview = service.previewChange(create)
        assertTrue(createPreview.issues.joinToString { it.message }, createPreview.accepted)
        val created = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepareChange(
                JmixEnvironmentChangeApplyRequest(
                    create,
                    requireNotNull(createPreview.planDigest),
                ),
            ),
        )
        assertTrue(created.issues.joinToString { it.message }, created.success)
        assertEquals(
            "PAYROLL_API_URL=https://sandbox.example\n",
            ProjectSourceText.read(
                requireNotNull(
                    ProjectFileResolver.getInstance(project)
                        .resolveFile(missingFile.relativePath)
                        ?.file,
                ),
            ),
        )
    }

    fun testReferencedVariableRemovalAndUnsafeImportsFailClosed() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "spring.config.import=optional:file:.env[.properties],optional:file:../outside.properties\n" +
                "payroll.url=\${PAYROLL_API_URL}\n",
        )
        myFixture.addFileToProject(".env", "PAYROLL_API_URL=https://sandbox.example\n")
        val service = JmixEnvironmentConfigurationService.getInstance(project)
        val workspace = service.inspect()
        assertTrue(workspace.issues.any { it.code == "JVW-ENV-IMPORT-READ-ONLY" })
        val file = workspace.files.single()

        val preview = service.previewChange(
            JmixEnvironmentChangeRequest(
                workspaceDigest = workspace.snapshotDigest,
                relativePath = file.relativePath,
                locator = file.locator,
                variableName = "PAYROLL_API_URL",
                mode = JmixEnvironmentChangeMode.REMOVE,
            ),
        )

        assertFalse(preview.accepted)
        assertEquals("JVW-ENV-VARIABLE-REFERENCED", preview.issues.single().code)
    }
}

package org.jmixworkbench.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator

class JmixProjectPropertiesIntegrationTest : BasePlatformTestCase() {
    fun testProjectServiceMapsCurrentIntellijFilesWithoutReturningDatasourceSecrets() {
        myFixture.addFileToProject(
            "settings.gradle",
            """rootProject.name = "payroll"""",
        )
        myFixture.addFileToProject(
            "build.gradle",
            """
            plugins {
                id 'java'
                id 'io.jmix' version '2.8.2'
            }
            group = 'com.company.payroll'
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(21)
                }
            }
            dependencies {
                implementation 'io.jmix.flowui:jmix-flowui-starter:2.8.2'
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            """
            server.port=8080
            jmix.core.additional-stores=loan
            main.datasource.url=jdbc:postgresql://localhost/payroll
            main.datasource.password=must-not-cross-jcef
            loan.datasource.url=jdbc:postgresql://localhost/loan
            loan.datasource.password=${'$'}{LOAN_PASSWORD:unsafe-default}
            """.trimIndent(),
        )

        val workspace = JmixProjectPropertiesService.getInstance(project).inspect()

        assertEquals("2.8.2", workspace.jmixVersion)
        assertEquals(21, workspace.targetJava)
        assertTrue(workspace.buildFiles.single().relativePath.endsWith("/build.gradle"))
        assertTrue(workspace.settingsFiles.single().relativePath.endsWith("/settings.gradle"))
        assertEquals(1, workspace.profiles.size)
        assertEquals(listOf("main", "loan"), workspace.profiles.single().stores.map { it.name })
        val serialized = workspace.toString()
        assertFalse(serialized.contains("must-not-cross-jcef"))
        assertFalse(serialized.contains("unsafe-default"))
        assertTrue(serialized.contains("••••••••"))
    }

    fun testProfilePreviewApplyUndoAndRedoAreExactAndCredentialSafe() {
        val original =
            "# preserve this comment\r\n" +
                "server.port : 8080\r\n" +
                "main.datasource.password=literal-database-secret\r\n" +
                "unrelated.api-token=unrelated-secret-must-stay-backend\r\n"
        val source = myFixture.addFileToProject(
            "src/main/resources/application.properties",
            original,
        ).virtualFile
        val service = JmixProjectPropertiesService.getInstance(project)
        val locator = service.inspect().profiles.single().locator
        val change = JmixApplicationPropertiesChangeRequest(
            profileLocator = locator,
            updates = listOf(
                JmixApplicationPropertyUpdate("server.port", "9090"),
                JmixApplicationPropertyUpdate("server.servlet.context-path", "/payroll"),
                JmixApplicationPropertyUpdate("main.datasource.password", "\${DB_PASSWORD}"),
            ),
        )

        val preview = service.previewProfileChange(change)

        assertTrue(preview.issues.joinToString { it.message }, preview.accepted)
        assertNotNull(preview.planDigest)
        val serializedPreview = preview.files.joinToString("\n") { file ->
            "${file.originalContent}\n${file.resultContent}"
        }
        assertTrue(serializedPreview.contains("Credential-safe focused preview"))
        assertTrue(serializedPreview.contains("server.port=8080"))
        assertTrue(serializedPreview.contains("server.port=9090"))
        assertTrue(serializedPreview.contains("main.datasource.password=••••••••"))
        assertTrue(serializedPreview.contains("main.datasource.password=\${DB_PASSWORD}"))
        assertFalse(serializedPreview.contains("literal-database-secret"))
        assertFalse(serializedPreview.contains("unrelated-secret-must-stay-backend"))
        assertFalse(serializedPreview.contains("unrelated.api-token"))

        val prepared = service.prepareProfileChange(
            JmixApplicationPropertiesChangeApplyRequest(
                change = change,
                expectedPlanDigest = requireNotNull(preview.planDigest),
            ),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)

        assertTrue(applied.issues.joinToString { it.message }, applied.success)
        val expected =
            "# preserve this comment\r\n" +
                "server.port : 9090\r\n" +
                "main.datasource.password=\${DB_PASSWORD}\r\n" +
                "unrelated.api-token=unrelated-secret-must-stay-backend\r\n" +
                "server.servlet.context-path=/payroll\r\n"
        assertEquals(expected, ProjectSourceText.read(source))

        val undone = WorkspaceHistoryService.getInstance(project).undo()
        assertTrue(undone.issues.joinToString { it.message }, undone.success)
        assertEquals(original, ProjectSourceText.read(source))

        val redone = WorkspaceHistoryService.getInstance(project).redo()
        assertTrue(redone.issues.joinToString { it.message }, redone.success)
        assertEquals(expected, ProjectSourceText.read(source))
    }

    fun testProfileChangeRejectsUnsafeValuesAtTheBackendBoundary() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\nmain.datasource.url=jdbc:postgresql://localhost/app\n",
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        val locator = service.inspect().profiles.single().locator
        val cases = listOf(
            JmixApplicationPropertyUpdate("main.datasource.password", "literal-secret") to
                "JVW-PROJECT-PROPERTIES-SECRET-LITERAL-DENIED",
            JmixApplicationPropertyUpdate("main.datasource.password", "\${DB_PASSWORD:}") to
                "JVW-PROJECT-PROPERTIES-SECRET-LITERAL-DENIED",
            JmixApplicationPropertyUpdate(
                "main.datasource.url",
                "jdbc:postgresql://payroll:secret@localhost/app",
            ) to "JVW-PROJECT-PROPERTIES-EMBEDDED-SECRET-DENIED",
            JmixApplicationPropertyUpdate(
                "main.datasource.url",
                "jdbc:oracle:thin:ledger/secret@localhost:1521/ledger",
            ) to "JVW-PROJECT-PROPERTIES-EMBEDDED-SECRET-DENIED",
            JmixApplicationPropertyUpdate("server.port", "70000") to
                "JVW-PROJECT-PROPERTIES-PORT-INVALID",
            JmixApplicationPropertyUpdate("server.servlet.context-path", "not/absolute") to
                "JVW-PROJECT-PROPERTIES-CONTEXT-PATH-INVALID",
            JmixApplicationPropertyUpdate("jmix.core.available-locales", "not a locale") to
                "JVW-PROJECT-PROPERTIES-LOCALES-INVALID",
            JmixApplicationPropertyUpdate("jmix.core.additional-stores", "loan,loan") to
                "JVW-PROJECT-PROPERTIES-STORES-INVALID",
            JmixApplicationPropertyUpdate("spring.profiles.active", "dev,dev") to
                "JVW-PROJECT-PROPERTIES-ACTIVE-PROFILES-INVALID",
            JmixApplicationPropertyUpdate("main.liquibase.enabled", "sometimes") to
                "JVW-PROJECT-PROPERTIES-LIQUIBASE-ENABLED-INVALID",
            JmixApplicationPropertyUpdate("spring.datasource.hikari.maximum-pool-size", "50") to
                "JVW-PROJECT-PROPERTIES-KEY-UNSUPPORTED",
            JmixApplicationPropertyUpdate("main.datasource.username", "line1\nline2") to
                "JVW-PROJECT-PROPERTIES-VALUE-INVALID",
        )

        cases.forEach { (update, expectedCode) ->
            val preview = service.previewProfileChange(
                JmixApplicationPropertiesChangeRequest(locator, listOf(update)),
            )
            assertFalse("Expected $expectedCode for ${update.key}", preview.accepted)
            assertEquals(expectedCode, preview.issues.single().code)
        }
    }

    fun testProfileChangeAcceptsExactEnvironmentSecretAndPreservesValueWhitespace() {
        val source = myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "main.datasource.username=old\nmain.datasource.password=\${OLD_PASSWORD}\n",
        ).virtualFile
        val service = JmixProjectPropertiesService.getInstance(project)
        val locator = service.inspect().profiles.single().locator
        val change = JmixApplicationPropertiesChangeRequest(
            locator,
            listOf(
                JmixApplicationPropertyUpdate("main.datasource.username", " payroll "),
                JmixApplicationPropertyUpdate("main.datasource.password", "\${NEW_PASSWORD}"),
            ),
        )
        val preview = service.previewProfileChange(change)

        assertTrue(preview.issues.joinToString { it.message }, preview.accepted)
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepareProfileChange(
                JmixApplicationPropertiesChangeApplyRequest(
                    change,
                    requireNotNull(preview.planDigest),
                ),
            ),
        )
        assertTrue(applied.issues.joinToString { it.message }, applied.success)
        assertEquals(
            "main.datasource.username=\\ payroll \nmain.datasource.password=\${NEW_PASSWORD}\n",
            ProjectSourceText.read(source),
        )
    }

    fun testProfileChangeFailsClosedForDuplicateAndContinuedSourceKeys() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\nserver.port=8081\n" +
                "jmix.core.available-locales=en|English,\\\n  bn|বাংলা\n",
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        val locator = service.inspect().profiles.single().locator

        val duplicate = service.previewProfileChange(
            JmixApplicationPropertiesChangeRequest(
                locator,
                listOf(JmixApplicationPropertyUpdate("server.port", "9090")),
            ),
        )
        assertFalse(duplicate.accepted)
        assertEquals("JVW-PROJECT-PROPERTIES-SOURCE-DUPLICATE", duplicate.issues.single().code)

        val continued = service.previewProfileChange(
            JmixApplicationPropertiesChangeRequest(
                locator,
                listOf(JmixApplicationPropertyUpdate("jmix.core.available-locales", "en,bn")),
            ),
        )
        assertFalse(continued.accepted)
        assertEquals(
            "JVW-PROJECT-PROPERTIES-CONTINUATION-READ-ONLY",
            continued.issues.single().code,
        )
    }

    fun testProfileApplyRejectsWrongDigestAndPostPreviewSourceEdit() {
        val source = myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\n",
        ).virtualFile
        val service = JmixProjectPropertiesService.getInstance(project)
        val locator = service.inspect().profiles.single().locator
        val change = JmixApplicationPropertiesChangeRequest(
            locator,
            listOf(JmixApplicationPropertyUpdate("server.port", "9090")),
        )
        val preview = service.previewProfileChange(change)
        assertTrue(preview.accepted)

        val wrongDigest = service.prepareProfileChange(
            JmixApplicationPropertiesChangeApplyRequest(change, "forged-digest"),
        )
        assertFalse(wrongDigest.plan.accepted)
        assertEquals("JVW-CHANGE-PREVIEW-STALE", wrongDigest.plan.issues.single().code)

        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(source, "server.port=8181\n")
        }
        val stale = service.prepareProfileChange(
            JmixApplicationPropertiesChangeApplyRequest(
                change,
                requireNotNull(preview.planDigest),
            ),
        )
        assertFalse(stale.plan.accepted)
        assertEquals("JVW-PROJECT-PROPERTIES-SOURCE-STALE", stale.plan.issues.single().code)
        assertEquals("server.port=8181\n", ProjectSourceText.read(source))
    }

    fun testProfileChangeRejectsNoOpAndDuplicateUpdatePayloads() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\n",
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        val locator = service.inspect().profiles.single().locator

        val noOp = service.previewProfileChange(
            JmixApplicationPropertiesChangeRequest(
                locator,
                listOf(JmixApplicationPropertyUpdate("server.port", "8080")),
            ),
        )
        assertFalse(noOp.accepted)
        assertEquals("JVW-PROJECT-PROPERTIES-NO-CHANGES", noOp.issues.single().code)

        val duplicatePayload = service.previewProfileChange(
            JmixApplicationPropertiesChangeRequest(
                locator,
                listOf(
                    JmixApplicationPropertyUpdate("server.port", "9090"),
                    JmixApplicationPropertyUpdate(" server.port ", "9191"),
                ),
            ),
        )
        assertFalse(duplicatePayload.accepted)
        assertEquals(
            "JVW-PROJECT-PROPERTIES-DUPLICATE-UPDATE",
            duplicatePayload.issues.single().code,
        )
    }

    fun testProfileChangeCannotTargetAnUnindexedPropertiesFile() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\n",
        )
        val unindexedContent = "server.port=8181\n"
        myFixture.addFileToProject(
            "scratch/application-hidden.properties",
            unindexedContent,
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        assertFalse(
            service.inspect().profiles.any {
                it.locator.relativePath == "scratch/application-hidden.properties"
            },
        )

        val preview = service.previewProfileChange(
            JmixApplicationPropertiesChangeRequest(
                profileLocator = SourceLocator(
                    relativePath = "scratch/application-hidden.properties",
                    revisionFingerprint = CanonicalDiscoveryJson.sha256(unindexedContent),
                ),
                updates = listOf(JmixApplicationPropertyUpdate("server.port", "9191")),
            ),
        )

        assertFalse(preview.accepted)
        assertEquals("JVW-PROJECT-PROPERTIES-TARGET-UNINDEXED", preview.issues.single().code)
    }

    fun testActiveProfilesCanOnlyBeChangedInDefaultApplicationProperties() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "spring.profiles.active=dev\n",
        )
        myFixture.addFileToProject(
            "src/main/resources/application-dev.properties",
            "server.port=8181\n",
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        val dev = service.inspect().profiles.single { it.profile == "dev" }

        val preview = service.previewProfileChange(
            JmixApplicationPropertiesChangeRequest(
                dev.locator,
                listOf(JmixApplicationPropertyUpdate("spring.profiles.active", "test")),
            ),
        )

        assertFalse(preview.accepted)
        assertEquals("JVW-PROJECT-PROPERTIES-ACTIVE-PROFILE-OWNER", preview.issues.single().code)
    }

    fun testProfileCreationIsRevisionBoundAndUndoable() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\n",
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        val anchor = service.inspect().profiles.single()
        val change = JmixApplicationProfileLifecycleRequest(
            mode = JmixApplicationProfileLifecycleMode.CREATE,
            profileLocator = anchor.locator,
            profileName = "staging",
        )

        val preview = service.previewProfileLifecycleChange(change)

        assertTrue(preview.issues.joinToString { it.message }, preview.accepted)
        assertEquals("CREATE", preview.files.single().mode.name)
        assertTrue(preview.files.single().resultContent.contains("staging"))
        val prepared = service.prepareProfileLifecycleChange(
            JmixApplicationProfileLifecycleApplyRequest(
                change = change,
                expectedPlanDigest = requireNotNull(preview.planDigest),
            ),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.issues.joinToString { it.message }, applied.success)
        val targetPath = preview.files.single().relativePath
        val target = requireNotNull(ProjectFileResolver.getInstance(project).resolveFile(targetPath)?.file)
        assertEquals(
            "# Profile-specific Jmix configuration for staging.\n",
            ProjectSourceText.read(target),
        )
        assertTrue(service.inspect().profiles.any { it.profile == "staging" })

        val undone = WorkspaceHistoryService.getInstance(project).undo()
        assertTrue(undone.issues.joinToString { it.message }, undone.success)
        assertNull(ProjectFileResolver.getInstance(project).resolveFile(targetPath))

        val redone = WorkspaceHistoryService.getInstance(project).redo()
        assertTrue(redone.issues.joinToString { it.message }, redone.success)
        assertEquals(
            "# Profile-specific Jmix configuration for staging.\n",
            ProjectSourceText.read(
                requireNotNull(ProjectFileResolver.getInstance(project).resolveFile(targetPath)?.file),
            ),
        )
    }

    fun testProfileRemovalDoesNotExposeSecretsAndRestoresExactSourceOnUndo() {
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\n",
        )
        val original =
            "# development profile\r\n" +
                "server.port=8181\r\n" +
                "main.datasource.password=must-never-cross-jcef\r\n"
        myFixture.addFileToProject(
            "src/main/resources/application-dev.properties",
            original,
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        val dev = service.inspect().profiles.single { it.profile == "dev" }
        val change = JmixApplicationProfileLifecycleRequest(
            mode = JmixApplicationProfileLifecycleMode.REMOVE,
            profileLocator = dev.locator,
            profileName = "dev",
        )

        val preview = service.previewProfileLifecycleChange(change)

        assertTrue(preview.issues.joinToString { it.message }, preview.accepted)
        assertEquals("DELETE", preview.files.single().mode.name)
        val serialized = preview.files.joinToString {
            "${it.originalContent}\n${it.resultContent}"
        }
        assertTrue(serialized.contains("Credential-safe deletion preview"))
        assertTrue(serialized.contains("Properties: 2"))
        assertTrue(serialized.contains("Secret-bearing properties: 1"))
        assertFalse(serialized.contains("must-never-cross-jcef"))
        val prepared = service.prepareProfileLifecycleChange(
            JmixApplicationProfileLifecycleApplyRequest(
                change = change,
                expectedPlanDigest = requireNotNull(preview.planDigest),
            ),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.issues.joinToString { it.message }, applied.success)
        val targetPath = preview.files.single().relativePath
        assertNull(ProjectFileResolver.getInstance(project).resolveFile(targetPath))

        val undone = WorkspaceHistoryService.getInstance(project).undo()
        assertTrue(undone.issues.joinToString { it.message }, undone.success)
        assertEquals(
            original,
            ProjectSourceText.read(
                requireNotNull(ProjectFileResolver.getInstance(project).resolveFile(targetPath)?.file),
            ),
        )

        val redone = WorkspaceHistoryService.getInstance(project).redo()
        assertTrue(redone.issues.joinToString { it.message }, redone.success)
        assertNull(ProjectFileResolver.getInstance(project).resolveFile(targetPath))
    }

    fun testProfileRemovalRejectsDefaultActiveAndStaleProfiles() {
        val defaultSource = myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "spring.profiles.active=dev\n",
        ).virtualFile
        val devSource = myFixture.addFileToProject(
            "src/main/resources/application-dev.properties",
            "server.port=8181\n",
        ).virtualFile
        val service = JmixProjectPropertiesService.getInstance(project)
        val profiles = service.inspect().profiles
        val defaultProfile = profiles.single { it.profile == "default" }
        val dev = profiles.single { it.profile == "dev" }

        val removeDefault = service.previewProfileLifecycleChange(
            JmixApplicationProfileLifecycleRequest(
                JmixApplicationProfileLifecycleMode.REMOVE,
                defaultProfile.locator,
                "default",
            ),
        )
        assertFalse(removeDefault.accepted)
        assertEquals("JVW-PROJECT-PROFILE-REMOVE-DEFAULT", removeDefault.issues.single().code)

        val removeActive = service.previewProfileLifecycleChange(
            JmixApplicationProfileLifecycleRequest(
                JmixApplicationProfileLifecycleMode.REMOVE,
                dev.locator,
                "dev",
            ),
        )
        assertFalse(removeActive.accepted)
        assertEquals("JVW-PROJECT-PROFILE-REMOVE-ACTIVE", removeActive.issues.single().code)

        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(defaultSource, "spring.profiles.active=test\n")
            VfsUtil.saveText(devSource, "server.port=8282\n")
        }
        val stale = service.previewProfileLifecycleChange(
            JmixApplicationProfileLifecycleRequest(
                JmixApplicationProfileLifecycleMode.REMOVE,
                dev.locator,
                "dev",
            ),
        )
        assertFalse(stale.accepted)
        assertEquals("JVW-PROJECT-PROFILE-LIFECYCLE-STALE", stale.issues.single().code)
    }

    fun testProfileRemovalRejectsExplicitAndDynamicProfileReferences() {
        val defaultSource = myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "spring.profiles.include=dev\n",
        ).virtualFile
        myFixture.addFileToProject(
            "src/main/resources/application-dev.properties",
            "server.port=8181\n",
        )
        val service = JmixProjectPropertiesService.getInstance(project)
        val dev = service.inspect().profiles.single { it.profile == "dev" }
        val change = JmixApplicationProfileLifecycleRequest(
            JmixApplicationProfileLifecycleMode.REMOVE,
            dev.locator,
            "dev",
        )

        val referenced = service.previewProfileLifecycleChange(change)
        assertFalse(referenced.accepted)
        assertEquals("JVW-PROJECT-PROFILE-REMOVE-REFERENCED", referenced.issues.single().code)

        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(defaultSource, "spring.profiles.include=\${PROFILE_INCLUDE}\n")
        }
        val dynamic = service.previewProfileLifecycleChange(change)
        assertFalse(dynamic.accepted)
        assertEquals(
            "JVW-PROJECT-PROFILE-REMOVE-DYNAMIC-REFERENCE",
            dynamic.issues.single().code,
        )
    }
}

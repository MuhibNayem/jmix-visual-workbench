package org.jmixworkbench.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}

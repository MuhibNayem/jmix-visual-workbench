package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jmixworkbench.model.DatabaseType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JmixProjectServiceIntegrationTest : HeavyPlatformTestCase() {
    fun testBasePackageIsRecoveredFromBuildDeclaredCustomJavaRoot() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                sourceSets {
                    named("main") {
                        java {
                            srcDir("domain-model")
                        }
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "domain-model/com/acme/payments/entity/Payment.java",
                """
                package com.acme.payments.entity;
                @JmixEntity
                public class Payment {}
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)

        val service = JmixProjectService.getInstance(project)
        service.refresh()

        assertEquals("com.acme.payments", service.getConfig()?.basePackage)
    }

    fun testCompositeAggregatorDiscoversDeepIncludedJmixBuildAndApplicationPackage() {
        val root = getOrCreateProjectBaseDir()
        val includedPrefix =
            "components/enterprise/region/payroll/platform/applications/loan-runtime"
        WriteAction.run<RuntimeException> {
            write(
                root,
                "settings.gradle.kts",
                """
                rootProject.name = "payroll-all"
                includeBuild("$includedPrefix")
                """.trimIndent(),
            )
            write(
                root,
                "build.gradle.kts",
                """
                plugins {
                    base
                }
                """.trimIndent(),
            )
            write(
                root,
                "$includedPrefix/build.gradle.kts",
                """
                plugins {
                    id("io.jmix") version "2.8.3"
                }
                group = "com.acme"
                jmix {
                    projectId.set("loan")
                }
                dependencies {
                    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
                }
                """.trimIndent(),
            )
            write(
                root,
                "$includedPrefix/src/main/kotlin/com/acme/loan/LoanApplication.kt",
                """
                package com.acme.loan

                import org.springframework.boot.autoconfigure.SpringBootApplication

                @SpringBootApplication
                class LoanApplication
                """.trimIndent(),
            )
            write(
                root,
                "node_modules/fake-jmix/build.gradle",
                """
                plugins {
                    id 'io.jmix' version '99.0.0'
                }
                """.trimIndent(),
            )
        }

        val service = JmixProjectService.getInstance(project)
        service.refresh()
        val config = service.getConfig()
        assertNotNull(config)

        assertTrue(service.isJmixProject())
        assertEquals("com.acme.loan", config!!.basePackage)
        assertEquals("2.8.3", config.jmixVersion)
        assertEquals("loan", config.projectId)
        assertEquals(DatabaseType.MYSQL, config.databaseType)
        assertEquals(
            "loan",
            service.projectIdForModule("$includedPrefix/src/main/kotlin/com/acme/loan"),
        )
        assertEquals(
            "loan",
            service.projectIdForModule(":${includedPrefix.replace('/', ':')}"),
        )
    }

    fun testClosestModuleProjectIdOverridesRootProjectIdForNestedSourcePaths() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "build.gradle",
                """
                plugins {
                    id 'io.jmix' version '2.7.4'
                }
                jmix {
                    projectId = 'suite'
                }
                """.trimIndent(),
            )
            write(
                root,
                "loan/build.gradle.kts",
                """
                plugins {
                    id("io.jmix") version "2.8.3"
                }
                jmix.projectId = "loan"
                """.trimIndent(),
            )
        }

        val service = JmixProjectService.getInstance(project)
        service.refresh()

        assertEquals("loan", service.projectIdForModule("loan/src/main/java/com/acme/loan"))
        assertEquals("suite", service.projectIdForModule("payroll/src/main/java/com/acme/payroll"))
        assertEquals("suite", service.projectIdForModule(""))
    }

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

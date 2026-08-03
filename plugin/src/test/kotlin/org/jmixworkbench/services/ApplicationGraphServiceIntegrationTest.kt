package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.RelationshipType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationGraphServiceIntegrationTest : HeavyPlatformTestCase() {

    fun testExistingRootChangesUseIncrementalInventoryAndSemanticContributions() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/payroll/Employee.java",
                "package com.acme.payroll; @JmixEntity public class Employee {}",
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        val service = ApplicationGraphService.getInstance(project)
        val initial = service.graph(forceRefresh = true)
        assertEquals(1, initial.scannedFiles)

        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/payroll/Department.java",
                "package com.acme.payroll; @JmixEntity public class Department {}",
            )
        }
        val added = service.graph()

        assertFalse(added.cacheHit)
        assertEquals(2, added.scannedFiles)
        assertEquals(1, added.changedFiles)
        assertEquals(1, added.reusedFiles)
        assertTrue(added.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "Department"
        })

        val cached = service.graph()
        assertTrue(cached.cacheHit)
        assertEquals(0, cached.changedFiles)
        assertEquals(2, cached.reusedFiles)
    }

    fun testUnsavedJvmDocumentInvalidatesGraphAndBecomesIndexedRevision() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/payroll/DraftPayroll.java",
                "package com.acme.payroll; @JmixEntity public class DraftPayroll {}",
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        val service = ApplicationGraphService.getInstance(project)
        val initial = service.graph(forceRefresh = true)
        assertTrue(initial.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "DraftPayroll"
        })
        val file = requireNotNull(
            root.findFileByRelativePath("src/main/java/com/acme/payroll/DraftPayroll.java"),
        )
        val documentManager = FileDocumentManager.getInstance()
        val document = requireNotNull(documentManager.getDocument(file))
        WriteAction.run<RuntimeException> {
            document.setText(
                "package com.acme.payroll; @JmixEntity public class ReviewedPayroll {}",
            )
        }
        assertTrue(documentManager.isDocumentUnsaved(document))

        val refreshed = service.graph()

        assertFalse(refreshed.cacheHit)
        assertTrue(refreshed.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "ReviewedPayroll"
        })
        assertFalse(refreshed.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "DraftPayroll"
        })
        assertTrue(String(file.contentsToByteArray(false), file.charset).contains("DraftPayroll"))
    }

    fun testMostSpecificImportedModuleOwnsFilesUnderOverlappingContentRoots() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(root, "settings.gradle.kts", """include(":payroll")""")
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            write(root, "payroll/build.gradle.kts", "plugins { java }")
            write(
                root,
                "payroll/src/main/java/com/acme/payroll/PayrollRun.java",
                "package com.acme.payroll; @JmixEntity public class PayrollRun {}",
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        val payrollRoot = requireNotNull(root.findFileByRelativePath("payroll"))
        val payrollSource = requireNotNull(root.findFileByRelativePath("payroll/src/main/java"))
        val payrollModule = WriteAction.compute<com.intellij.openapi.module.Module, RuntimeException> {
            val created = ModuleManager.getInstance(project).newModule(
                "${root.path}/payroll-module.iml",
                ModuleType.EMPTY.id,
            )
            val model = ModuleRootManager.getInstance(created).modifiableModel
            val entry = model.addContentEntry(payrollRoot)
            entry.addSourceFolder(payrollSource, false)
            model.commit()
            created
        }
        WriteAction.run<RuntimeException> {
            val model = ModuleRootManager.getInstance(module).modifiableModel
            model.addModuleOrderEntry(payrollModule)
            model.commit()
        }

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val entity = assertNotNull(
            graph.artifacts.singleOrNull {
                it.kind == ArtifactKind.ENTITY && it.displayName == "PayrollRun"
            },
            "Entity in the nested imported module was not indexed.",
        )

        assertEquals(payrollModule.name, entity.owner.moduleId)
        assertTrue(graph.modules.any { it.moduleId == payrollModule.name && it.indexedFileCount == 1 })
        assertFalse(graph.modules.any {
            it.moduleId.endsWith("#:payroll") && it.moduleId != payrollModule.name
        })
        val sourceModuleArtifact = graph.artifacts.single {
            it.kind == ArtifactKind.MODULE && it.owner.moduleId == module.name
        }
        val payrollModuleArtifact = graph.artifacts.single {
            it.kind == ArtifactKind.MODULE && it.owner.moduleId == payrollModule.name
        }
        assertTrue(graph.relationships.any {
            it.sourceArtifactId == sourceModuleArtifact.id &&
                it.targetArtifactId == payrollModuleArtifact.id &&
                it.type == RelationshipType.DEPENDS_ON_MODULE
        })
        assertEquals(0, graph.indexHealth.ambiguousOwnershipFileCount)
    }

    fun testIndexesEveryConfiguredSourceSetIncludingGeneratedSources() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/core/Employee.java",
                "package com.acme.core; @JmixEntity public class Employee {}",
            )
            write(
                root,
                "src/integrationTest/java/com/acme/core/PayrollFixture.java",
                "package com.acme.core; public class PayrollFixture {}",
            )
            write(
                root,
                "build/generated/sources/java/com/acme/generated/GeneratedLedger.java",
                "package com.acme.generated; @JmixEntity public class GeneratedLedger {}",
            )
            write(
                root,
                "src/main/resources/com/acme/core/employee-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="employee-list-view">
                  <layout><span id="title"/></layout>
                </view>
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/integrationTest/java")),
            JavaSourceRootType.TEST_SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("build/generated/sources/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val moduleCoverage = graph.modules.single { it.moduleId == module.name }
        val entityNames = graph.artifacts
            .filter { it.kind == ArtifactKind.ENTITY }
            .map { it.displayName }
            .toSet()

        assertEquals(setOf("Employee", "GeneratedLedger"), entityNames)
        assertEquals(4, moduleCoverage.sourceRootCount)
        assertEquals(0, moduleCoverage.fallbackContentRootCount)
        assertEquals(setOf("main", "integrationTest", "generated-java"), moduleCoverage.sourceSets.toSet())
        assertEquals(4, moduleCoverage.indexedFileCount)
        assertEquals(4, graph.indexHealth.sourceRootCount)
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.VIEW_DESCRIPTOR && it.displayName == "employee-list-view"
        })
        assertTrue(graph.indexHealth.complete)
    }

    fun testDependencyLocksAndCompiledResourceCopiesAreNotApplicationSources() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/resources/application.yml",
                "spring:\n  profiles:\n    active: enterprise",
            )
            write(
                root,
                "src/main/resources/pnpm-lock.yaml",
                "lockfileVersion: 9\n'@polymer/iron-icon': 3.0.1\npackages:\n  values:\n    - one",
            )
            write(
                root,
                "build/resources/main/com/acme/menu.xml",
                "<menu-config><menu></wrong></menu-config>",
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("build/resources/main")),
            JavaResourceRootType.RESOURCE,
        )

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)

        assertEquals(1, graph.scannedFiles)
        assertEquals(0, graph.parseErrorFiles)
        assertTrue(graph.indexHealth.complete)
        assertFalse(graph.artifacts.any {
            it.sourceLocator.relativePath.endsWith("pnpm-lock.yaml") ||
                "/build/resources/" in it.sourceLocator.relativePath
        })
    }

    fun testSyntaxErrorsAreExcludedAndMakeIndexHealthPartial() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/core/ValidEntity.java",
                "package com.acme.core; @JmixEntity public class ValidEntity {}",
            )
            write(
                root,
                "src/main/java/com/acme/core/BrokenEntity.java",
                "package com.acme.core; @JmixEntity public class BrokenEntity {",
            )
            write(
                root,
                "src/main/resources/com/acme/core/broken-view.xml",
                """<view id="broken-view"><layout></view>""",
            )
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

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)

        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "ValidEntity"
        })
        assertFalse(graph.artifacts.any { it.displayName == "BrokenEntity" })
        assertEquals(2, graph.parseErrorFiles)
        assertEquals(2, graph.indexHealth.parseErrorFileCount)
        assertFalse(graph.indexHealth.complete)
        assertTrue(graph.diagnostics.any { it.reasonCode == "JVW-INDEX-JVM-SYNTAX-ERROR" })
        assertTrue(graph.diagnostics.any { it.reasonCode == "P2_XML_MALFORMED" })
        assertTrue(graph.diagnostics.any { it.reasonCode == "JVW-INDEX-PARSE-ERRORS" })
    }

    fun testRecoversNestedGradleModuleMissingFromImportedSourceModelWithoutHidingCoverageGap() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "settings.gradle.kts",
                """
                rootProject.name = "enterprise-suite"
                include(":payroll:core")
                """.trimIndent(),
            )
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            write(root, "payroll/core/build.gradle.kts", "plugins { java }")
            write(
                root,
                "src/main/java/com/acme/Application.java",
                "package com.acme; public class Application {}",
            )
            write(
                root,
                "payroll/core/src/main/java/com/acme/payroll/PayrollLedger.java",
                "package com.acme.payroll; @JmixEntity public class PayrollLedger {}",
            )
            write(
                root,
                "payroll/core/src/main/resources/com/acme/payroll/payroll-ledger-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="payroll-ledger-list-view">
                  <layout><dataGrid id="ledgersDataGrid"/></layout>
                </view>
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val recoveredModule = assertNotNull(
            graph.modules.singleOrNull { it.moduleId.endsWith("#:payroll:core") },
            "Recovered Gradle module missing. Indexed modules: ${graph.modules.map { it.moduleId }}",
        )

        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "PayrollLedger"
        })
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.VIEW_DESCRIPTOR && it.displayName == "payroll-ledger-list-view"
        })
        assertEquals("build:.", recoveredModule.buildIds.single())
        assertEquals(2, recoveredModule.discoveredSourceRootCount)
        assertEquals(setOf("main"), recoveredModule.sourceSets.toSet())
        assertEquals(2, recoveredModule.indexedFileCount)
        assertEquals(2, graph.indexHealth.discoveredSourceRootCount)
        assertEquals(1, graph.indexHealth.recoveredModuleCount)
        assertFalse(graph.indexHealth.complete)
        assertTrue(graph.diagnostics.any {
            it.reasonCode == "JVW-INDEX-GRADLE-SYNC-INCOMPLETE"
        })

        WriteAction.run<RuntimeException> {
            VfsUtil.saveText(
                requireNotNull(
                    root.findFileByRelativePath(
                        "payroll/core/src/main/java/com/acme/payroll/PayrollLedger.java",
                    ),
                ),
                """
                package com.acme.payroll;
                @JmixEntity public class PayrollLedger { private java.math.BigDecimal balance; }
                """.trimIndent(),
            )
        }
        val incrementallyRefreshed = ApplicationGraphService.getInstance(project).graph()
        val unchanged = ApplicationGraphService.getInstance(project).graph()

        assertFalse(incrementallyRefreshed.cacheHit)
        assertEquals(1, incrementallyRefreshed.changedFiles)
        assertEquals(graph.scannedFiles - 1, incrementallyRefreshed.reusedFiles)
        assertTrue(unchanged.cacheHit)
        assertEquals(0, unchanged.changedFiles)
    }

    fun testMapsBuildDeclaredCustomRootsAndSourceLessEnterpriseModules() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "settings.gradle.kts",
                """
                rootProject.name = "enterprise-suite"
                include(":benefits", ":reports", ":audit")
                project(":audit").projectDir = file("platform/audit-empty")
                """.trimIndent(),
            )
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            write(
                root,
                "benefits/build.gradle.kts",
                """
                plugins { java }
                dependencies {
                    implementation(project(":reports"))
                    runtimeOnly(projects.reports)
                }
                sourceSets {
                    named("main") {
                        java {
                            srcDir("domain-model")
                        }
                        resources.srcDirs("descriptor-resources")
                    }
                }
                """.trimIndent(),
            )
            write(root, "reports/build.gradle.kts", "plugins { java }")
            write(root, "platform/audit-empty/.keep", "")
            write(
                root,
                "benefits/domain-model/com/acme/benefits/BenefitClaim.java",
                "package com.acme.benefits; @JmixEntity public class BenefitClaim {}",
            )
            write(
                root,
                "benefits/descriptor-resources/com/acme/benefits/benefit-claim-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="benefit-claim-list-view">
                  <layout><dataGrid id="claimsDataGrid"/></layout>
                </view>
                """.trimIndent(),
            )
            write(
                root,
                "benefits/descriptor-resources/application-benefits.yml",
                """
                jmix:
                  rest:
                    services-config: com/acme/benefits/rest-services.xml
                """.trimIndent(),
            )
            write(
                root,
                "benefits/descriptor-resources/db/changelog/001-benefit.sql",
                """
                --liquibase formatted sql
                --changeset benefits:claim
                create table ACME_BENEFIT_CLAIM (ID uuid not null);
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val benefits = assertNotNull(
            graph.modules.singleOrNull { it.moduleId.endsWith("#:benefits") },
            "Custom-root module missing. Indexed modules: ${graph.modules.map { it.moduleId }}",
        )
        val reports = assertNotNull(
            graph.modules.singleOrNull { it.moduleId.endsWith("#:reports") },
            "Source-less module missing. Indexed modules: ${graph.modules.map { it.moduleId }}",
        )
        val audit = assertNotNull(
            graph.modules.singleOrNull { it.moduleId.endsWith("#:audit") },
            "Settings-declared projectDir module missing. Indexed modules: ${graph.modules.map { it.moduleId }}",
        )
        val destinations = ProjectSourceDestinationService.getInstance(project)
        assertTrue(
            destinations.productionJava(graph).any {
                it.moduleId == benefits.moduleId &&
                    it.sourceRoot == "benefits/domain-model"
            },
            "The exact Gradle-declared custom Java root was not exposed for generation.",
        )
        assertTrue(
            destinations.productionResources(graph).any {
                it.moduleId == benefits.moduleId &&
                    it.sourceRoot == "benefits/descriptor-resources"
            },
            "The exact Gradle-declared custom resource root was not exposed for generation.",
        )
        assertTrue(
            destinations.testJava(graph).any {
                it.moduleId == benefits.moduleId &&
                    it.sourceRoot == "benefits/src/test/java"
            },
            "A safe module-relative test destination was not derived for the recovered module.",
        )
        assertEquals("benefits", benefits.moduleRoot)
        assertTrue(benefits.sourceRoots.any {
            it.relativePath == "benefits/domain-model" &&
                it.kind == ApplicationGraphSourceRootKind.JAVA &&
                it.recovered
        })
        assertTrue(benefits.sourceRoots.any {
            it.relativePath == "benefits/descriptor-resources" &&
                it.kind == ApplicationGraphSourceRootKind.RESOURCES &&
                it.recovered
        })
        val entity = assertNotNull(
            graph.artifacts.singleOrNull {
                it.kind == ArtifactKind.ENTITY && it.displayName == "BenefitClaim"
            },
            "Entity from the custom Java root was not indexed.",
        )
        val moduleArtifact = assertNotNull(
            graph.artifacts.singleOrNull {
                it.kind == ArtifactKind.MODULE && it.owner.moduleId == benefits.moduleId
            },
            "Recovered module is absent from the semantic topology.",
        )
        val sourceSetArtifact = assertNotNull(
            graph.artifacts.singleOrNull {
                it.kind == ArtifactKind.SOURCE_SET &&
                    it.owner.moduleId == benefits.moduleId &&
                    it.owner.sourceSetId == "main"
            },
            "Recovered source set is absent from the semantic topology.",
        )
        val buildArtifact = assertNotNull(
            graph.artifacts.singleOrNull {
                it.kind == ArtifactKind.BUILD && it.semanticKey == "build:."
            },
            "Owning build is absent from the semantic topology.",
        )
        val reportsArtifact = assertNotNull(
            graph.artifacts.singleOrNull {
                it.kind == ArtifactKind.MODULE && it.owner.moduleId == reports.moduleId
            },
            "Dependency target module is absent from the semantic topology.",
        )

        assertEquals(benefits.moduleId, entity.owner.moduleId)
        assertEquals("main", entity.owner.sourceSetId)
        assertEquals(2, benefits.discoveredSourceRootCount)
        assertEquals(setOf("main"), benefits.sourceSets.toSet())
        assertEquals(4, benefits.indexedFileCount)
        assertEquals(listOf("build:."), benefits.buildIds)
        assertEquals(0, reports.indexedFileCount)
        assertEquals(0, reports.sourceRootCount)
        assertEquals(listOf("build:."), reports.buildIds)
        assertEquals(0, audit.indexedFileCount)
        assertEquals(0, audit.sourceRootCount)
        assertEquals(listOf("build:."), audit.buildIds)
        assertEquals(3, graph.indexHealth.recoveredModuleCount)
        assertTrue(graph.relationships.any {
            it.sourceArtifactId == buildArtifact.id && it.targetArtifactId == moduleArtifact.id
        })
        assertTrue(graph.relationships.any {
            it.sourceArtifactId == moduleArtifact.id && it.targetArtifactId == sourceSetArtifact.id
        })
        assertTrue(graph.relationships.any {
            it.sourceArtifactId == sourceSetArtifact.id && it.targetArtifactId == entity.id
        })
        assertTrue(graph.relationships.any {
            it.sourceArtifactId == moduleArtifact.id &&
                it.targetArtifactId == reportsArtifact.id &&
                it.type == RelationshipType.DEPENDS_ON_MODULE
        })
        assertEquals(0, graph.indexHealth.unresolvedModuleDependencyCount)
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.VIEW_DESCRIPTOR &&
                it.displayName == "benefit-claim-list-view" &&
                it.owner.moduleId == benefits.moduleId
        })
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.CONFIGURATION_PROPERTY &&
                it.displayName == "jmix.rest.services-config" &&
                it.owner.moduleId == benefits.moduleId
        })
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.LIQUIBASE_CHANGESET &&
                it.displayName == "claim" &&
                it.owner.moduleId == benefits.moduleId
        })
        assertFalse(graph.indexHealth.complete)
        assertTrue(graph.diagnostics.any { it.reasonCode == "JVW-INDEX-GRADLE-SYNC-INCOMPLETE" })
    }

    fun testRecoversAndConnectsSixteenRemappedEnterpriseModulesDeterministically() {
        val root = getOrCreateProjectBaseDir()
        val paths = (1..16).map { ":domain${it.toString().padStart(2, '0')}" }
        WriteAction.run<RuntimeException> {
            write(
                root,
                "settings.gradle.kts",
                buildString {
                    append("rootProject.name = \"enterprise-payroll\"\n")
                    append("include(")
                    append(paths.joinToString { "\"$it\"" })
                    append(")\n")
                    paths.forEachIndexed { index, gradlePath ->
                        append("project(\"").append(gradlePath).append("\").projectDir = file(\"domains/")
                            .append((index + 1).toString().padStart(2, '0')).append("\")\n")
                    }
                },
            )
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            paths.forEachIndexed { index, gradlePath ->
                val number = (index + 1).toString().padStart(2, '0')
                val dependency = paths.getOrNull(index - 1)
                    ?.let { "dependencies { implementation(project(\"$it\")) }" }
                    .orEmpty()
                write(
                    root,
                    "domains/$number/build.gradle.kts",
                    "plugins { java }\n$dependency",
                )
                write(
                    root,
                    "domains/$number/src/main/java/com/acme/domain$number/Domain${number}Aggregate.java",
                    """
                    package com.acme.domain$number;
                    @JmixEntity
                    public class Domain${number}Aggregate {
                        private java.util.UUID id;
                    }
                    """.trimIndent(),
                )
            }
        }
        PsiTestUtil.addContentRoot(module, root)

        val first = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val second = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val recovered = first.modules.filter { coverage ->
            paths.any { coverage.moduleId.endsWith("#$it") }
        }
        val entities = first.artifacts.filter {
            it.kind == ArtifactKind.ENTITY && it.displayName.startsWith("Domain")
        }
        val dependencies = first.relationships.filter { it.type == RelationshipType.DEPENDS_ON_MODULE }

        assertEquals(16, recovered.size, recovered.map { it.moduleId }.toString())
        assertEquals(16, entities.size, entities.map { it.displayName }.toString())
        assertTrue(recovered.all { "main" in it.sourceSets }, recovered.map { it.sourceSets }.toString())
        assertTrue(recovered.all { it.indexedFileCount >= 1 })
        assertEquals(15, dependencies.count { it.targetArtifactId != null })
        assertEquals(0, first.indexHealth.unresolvedModuleDependencyCount)
        assertEquals(0, first.indexHealth.ambiguousOwnershipFileCount)
        assertEquals(first.snapshotDigest, second.snapshotDigest)
        assertEquals(
            first.artifacts.map { it.id },
            second.artifacts.map { it.id },
            "Repeated indexing must preserve deterministic artifact identity and ordering.",
        )
    }

    fun testRecoversCommonEnterpriseGradleDirectoryAndSourceSetDslVariants() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "settings.gradle.kts",
                """
                rootProject.name = "directory-dsl-suite"
                include(":ledger", ":payments", ":documents")
                project(":ledger").projectDir = File(rootDir, "bounded-contexts/ledger")
                project(":payments").projectDir = settingsDir.resolve("bounded-contexts/payments")
                project(":documents").projectDir = layout.projectDirectory.dir("bounded-contexts/documents")
                """.trimIndent(),
            )
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            write(
                root,
                "bounded-contexts/ledger/build.gradle.kts",
                """
                plugins { java }
                sourceSets {
                    named("main") {
                        java.setSrcDirs(listOf("domain/java"))
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "bounded-contexts/payments/build.gradle",
                """
                plugins { id 'java' }
                sourceSets {
                    main {
                        java.srcDirs = ['model/java']
                    }
                }
                dependencies { implementation project(':ledger') }
                """.trimIndent(),
            )
            write(
                root,
                "bounded-contexts/documents/build.gradle.kts",
                """
                plugins { java }
                sourceSets.named("main") {
                    resources.srcDirs("flow-resources")
                }
                dependencies { implementation(project(":payments")) }
                """.trimIndent(),
            )
            write(
                root,
                "bounded-contexts/ledger/domain/java/com/acme/ledger/LedgerAccount.java",
                "package com.acme.ledger; @JmixEntity public class LedgerAccount {}",
            )
            write(
                root,
                "bounded-contexts/payments/model/java/com/acme/payments/PaymentOrder.java",
                "package com.acme.payments; @JmixEntity public class PaymentOrder {}",
            )
            write(
                root,
                "bounded-contexts/documents/flow-resources/com/acme/document/document-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="document-list-view">
                  <layout><dataGrid id="documentsDataGrid"/></layout>
                </view>
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val recovered = graph.modules.filter { it.moduleId.startsWith("gradle:") }

        assertEquals(3, recovered.size, recovered.map { it.moduleId }.toString())
        assertTrue(recovered.any { it.moduleId.endsWith("#:ledger") && it.sourceSets == listOf("main") })
        assertTrue(recovered.any { it.moduleId.endsWith("#:payments") && it.sourceSets == listOf("main") })
        assertTrue(recovered.any { it.moduleId.endsWith("#:documents") && it.sourceSets == listOf("main") })
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "LedgerAccount"
        })
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "PaymentOrder"
        })
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.VIEW_DESCRIPTOR && it.displayName == "document-list-view"
        })
        assertEquals(
            2,
            graph.relationships.count {
                it.type == RelationshipType.DEPENDS_ON_MODULE && it.targetArtifactId != null
            },
        )
        assertEquals(0, graph.indexHealth.unresolvedModuleDependencyCount)
        assertEquals(0, graph.indexHealth.ambiguousOwnershipFileCount)
    }

    fun testQualifiesDuplicateGradlePathsAcrossCompositeBuilds() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "settings.gradle.kts",
                """
                rootProject.name = "bank-suite"
                include(":core", ":app")
                includeBuild("risk-platform")
                """.trimIndent(),
            )
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            write(root, "core/build.gradle.kts", "plugins { java }")
            write(
                root,
                "app/build.gradle.kts",
                "plugins { java }\ndependencies { implementation(project(\":core\")) }",
            )
            write(
                root,
                "core/src/main/java/com/acme/bank/BankCore.java",
                "package com.acme.bank; @JmixEntity public class BankCore {}",
            )
            write(
                root,
                "risk-platform/settings.gradle.kts",
                """
                rootProject.name = "risk-platform"
                include(":core", ":engine")
                """.trimIndent(),
            )
            write(root, "risk-platform/build.gradle.kts", "plugins { java }")
            write(root, "risk-platform/core/build.gradle.kts", "plugins { java }")
            write(
                root,
                "risk-platform/engine/build.gradle.kts",
                "plugins { java }\ndependencies { implementation(project(\":core\")) }",
            )
            write(
                root,
                "risk-platform/core/src/main/java/com/acme/risk/RiskCore.java",
                "package com.acme.risk; @JmixEntity public class RiskCore {}",
            )
        }
        PsiTestUtil.addContentRoot(module, root)

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val coreModules = graph.modules.filter { it.moduleId.endsWith("#:core") }
        val moduleArtifacts = graph.artifacts.filter { it.kind == ArtifactKind.MODULE }
        val dependencyPairs = graph.relationships
            .filter { it.type == RelationshipType.DEPENDS_ON_MODULE && it.targetArtifactId != null }
            .mapNotNull { relationship ->
                val source = moduleArtifacts.singleOrNull { it.id == relationship.sourceArtifactId }
                val target = moduleArtifacts.singleOrNull { it.id == relationship.targetArtifactId }
                if (source == null || target == null) null else source.owner.buildId to target.owner.buildId
            }

        assertEquals(2, coreModules.size, coreModules.map { it.moduleId }.toString())
        assertEquals(2, coreModules.flatMap { it.buildIds }.distinct().size)
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "BankCore"
        })
        assertTrue(graph.artifacts.any {
            it.kind == ArtifactKind.ENTITY && it.displayName == "RiskCore"
        })
        assertEquals(2, dependencyPairs.size, dependencyPairs.toString())
        assertTrue(dependencyPairs.all { (sourceBuild, targetBuild) -> sourceBuild == targetBuild })
        assertEquals(0, graph.indexHealth.unresolvedModuleDependencyCount)
        assertEquals(0, graph.indexHealth.ambiguousOwnershipFileCount)
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

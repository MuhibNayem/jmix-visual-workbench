package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.generator.EventListenerGenerator
import org.jmixworkbench.model.EntitySourceLanguage
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EntityEventListenerServiceTest : HeavyPlatformTestCase() {
    fun testCreatesCurrentJavaListenerAndIndexesEveryHandlerAgainstExactEntity() {
        val root = createFixture()
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val entity = schema.entities.single { it.className == "LoanApp" }
        val request = EntityEventListenerRequest(
            entitySource = entity.sourceLocator,
            className = "LoanAppEventListener",
            packageName = "com.acme.loan.listener",
            sourceLanguage = EntitySourceLanguage.JAVA,
            events = EventListenerGenerator.ListenerEvent.entries,
            afterCommitRequiresNewTransaction = true,
        )

        val preview = EntityEventListenerService.getInstance(project).preview(request)
        assertTrue(preview.accepted, preview.issues.joinToString())
        val file = preview.files.single()
        assertTrue(file.relativePath.endsWith("src/main/java/com/acme/loan/listener/LoanAppEventListener.java"))
        assertContains(file.resultContent, "@Component(\"loanAppEventListener\")")
        assertContains(file.resultContent, "@Transactional(propagation = Propagation.REQUIRES_NEW)")
        assertFalse(file.resultContent.contains("BeforeInsertEntityListener"))

        val prepared = EntityEventListenerService.getInstance(project).prepareApply(
            EntityEventListenerApplyRequest(request, requireNotNull(preview.planDigest)),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString())
        assertNotNull(root.findFileByRelativePath(file.relativePath))

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val entityArtifact = graph.artifacts.single {
            it.kind == ArtifactKind.ENTITY && it.displayName == "LoanApp"
        }
        val listeners = graph.artifacts.filter {
            it.kind == ArtifactKind.EVENT_LISTENER &&
                it.sourceLocator.relativePath == file.relativePath
        }
        assertTrue(listeners.size == 4, "Expected four indexed listener methods: $listeners")
        assertTrue(
            listeners.all { listener ->
                graph.relationships.any {
                    it.sourceArtifactId == listener.id &&
                        it.targetArtifactId == entityArtifact.id &&
                        it.type == RelationshipType.LISTENS_TO
                }
            },
            "Every generated listener method must be linked to LoanApp.",
        )
    }

    fun testKotlinPreviewUsesMatchingModuleAndRejectsUnsafeTransactionSelection() {
        createFixture()
        val entity = SchemaWorkspaceService.getInstance(project)
            .load(forceRefresh = true)
            .entities
            .single { it.className == "LoanApp" }
        val service = EntityEventListenerService.getInstance(project)
        val kotlin = EntityEventListenerRequest(
            entitySource = entity.sourceLocator,
            className = "LoanAppEventListener",
            packageName = "com.acme.loan.listener",
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            events = listOf(EventListenerGenerator.ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT),
            afterCommitRequiresNewTransaction = false,
        )

        val preview = service.preview(kotlin)
        assertTrue(preview.accepted, preview.issues.joinToString())
        assertTrue(preview.files.single().relativePath.endsWith("src/main/kotlin/com/acme/loan/listener/LoanAppEventListener.kt"))
        assertContains(preview.files.single().resultContent, "EntityChangedEvent<LoanApp>")
        assertFalse(preview.files.single().resultContent.contains("Propagation.REQUIRES_NEW"))

        val rejected = service.preview(
            kotlin.copy(
                events = listOf(EventListenerGenerator.ListenerEvent.ENTITY_SAVING),
                afterCommitRequiresNewTransaction = true,
            ),
        )
        assertFalse(rejected.accepted)
        assertTrue(rejected.issues.any { it.code == "JVW-ENTITY-LISTENER-TRANSACTION-INVALID" })

        val unknownEvent = service.preview(kotlin.copy(events = listOf(null)))
        assertFalse(unknownEvent.accepted)
        assertTrue(unknownEvent.issues.any { it.code == "JVW-ENTITY-LISTENER-EVENTS-INVALID" })
    }

    fun testStaleEntityAndCreateCollisionFailClosed() {
        val root = createFixture()
        val service = EntityEventListenerService.getInstance(project)
        val entity = SchemaWorkspaceService.getInstance(project)
            .load(forceRefresh = true)
            .entities
            .single { it.className == "LoanApp" }
        val request = EntityEventListenerRequest(
            entitySource = entity.sourceLocator,
            className = "LoanAppEventListener",
            packageName = "com.acme.loan.listener",
            events = listOf(EventListenerGenerator.ListenerEvent.ENTITY_SAVING),
        )
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/loan/listener/LoanAppEventListener.java",
                "package com.acme.loan.listener; public class LoanAppEventListener {}",
            )
        }
        val collision = service.preview(request)
        assertFalse(collision.accepted)
        assertTrue(collision.issues.any { it.code == "JVW-CHANGE-CREATE-CONFLICT" })

        WriteAction.run<RuntimeException> {
            val source = requireNotNull(root.findFileByRelativePath(entity.sourceLocator.relativePath))
            VfsUtil.saveText(source, VfsUtil.loadText(source) + "\n// changed\n")
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val stale = service.preview(request)
        assertFalse(stale.accepted)
        assertTrue(stale.issues.any { it.code == "JVW-ENTITY-LISTENER-SOURCE-STALE" })
    }

    private fun createFixture(): VirtualFile {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "settings.gradle.kts",
                "rootProject.name = \"loan\"",
            )
            write(
                root,
                "build.gradle.kts",
                "plugins { id(\"io.jmix\") version \"2.8.3\" }\ngroup = \"com.acme.loan\"",
            )
            write(
                root,
                "src/main/java/com/acme/loan/entity/LoanApp.java",
                """
                package com.acme.loan.entity;

                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @JmixEntity(name = "loan_LoanApp")
                @Entity
                public class LoanApp {
                    @Id
                    private java.util.UUID id;
                }
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        return root
    }

    private fun write(root: VirtualFile, path: String, content: String) {
        val parent = VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/'))
            ?: error("Cannot create ${path.substringBeforeLast('/')}")
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}

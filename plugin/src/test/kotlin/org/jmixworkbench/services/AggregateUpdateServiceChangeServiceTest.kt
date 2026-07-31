package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.SourceLocator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AggregateUpdateServiceChangeServiceTest : HeavyPlatformTestCase() {
    fun testJavaAggregateServiceAndControllerAreOneConstrainedAtomicPreview() {
        val fixture = fixture(kotlinController = false)

        val preview = AggregateUpdateServiceChangeService.getInstance(project).preview(fixture.request)

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(2, preview.files.size)
        val service = preview.files.single { it.mode.name == "CREATE" }
        val controller = preview.files.single { it.mode.name == "MODIFY" }
        assertTrue(service.relativePath.endsWith("/LoanAppUpdateService.java"))
        assertTrue(service.resultContent.contains("@Transactional"))
        assertTrue(service.resultContent.contains("dataManager.save(Objects.requireNonNull(saveContext"))
        assertFalse(service.resultContent.contains("dataManager.unconstrained"))
        assertTrue(controller.resultContent.contains("@Install(target = Target.DATA_CONTEXT)"))
        assertTrue(controller.resultContent.contains("loanAppUpdateService.saveChanges(saveContext)"))
        assertTrue(controller.resultContent.contains("// handwritten controller logic remains"))
        assertNotNull(preview.planDigest)
    }

    fun testKotlinControllerWiringUsesPsiBoundaryAndPreservesHandwrittenLogic() {
        val fixture = fixture(kotlinController = true)

        val preview = AggregateUpdateServiceChangeService.getInstance(project).preview(fixture.request)

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val service = preview.files.single { it.mode.name == "CREATE" }
        val controller = preview.files.single { it.mode.name == "MODIFY" }
        assertTrue(service.relativePath.endsWith("/LoanAppUpdateService.kt"))
        assertTrue(service.resultContent.contains("fun saveChanges(saveContext: SaveContext): Set<Any>"))
        assertTrue(controller.resultContent.contains("private lateinit var loanAppUpdateService:"))
        assertTrue(controller.resultContent.contains("Target.DATA_CONTEXT"))
        assertTrue(controller.resultContent.contains("loanAppUpdateService.saveChanges(saveContext)"))
        assertTrue(controller.resultContent.contains("// handwritten Kotlin controller logic remains"))
    }

    fun testApprovedTwoFilePlanAppliesAtomicallyAndUndoRestoresTheExactProject() {
        val fixture = fixture(kotlinController = false)
        val service = AggregateUpdateServiceChangeService.getInstance(project)
        val preview = service.preview(fixture.request)
        val controllerPreview = preview.files.single { it.mode.name == "MODIFY" }
        val servicePreview = preview.files.single { it.mode.name == "CREATE" }
        val prepared = service.prepareApply(
            AggregateUpdateServiceApplyRequest(
                change = fixture.request,
                expectedPlanDigest = requireNotNull(preview.planDigest),
            ),
        )

        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)

        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        val root = getOrCreateProjectBaseDir()
        assertEquals(
            controllerPreview.resultContent,
            VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(controllerPreview.relativePath))),
        )
        assertEquals(
            servicePreview.resultContent,
            VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(servicePreview.relativePath))),
        )

        val undone = WorkspaceHistoryService.getInstance(project).undo()
        assertTrue(undone.success, undone.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(
            controllerPreview.originalContent,
            VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(controllerPreview.relativePath))),
        )
        assertTrue(root.findFileByRelativePath(servicePreview.relativePath) == null)
    }

    fun testForgedEntityEvidenceAndStaleControllerRevisionFailClosed() {
        val fixture = fixture(kotlinController = false)
        val forgedEntity = fixture.request.copy(
            entitySource = fixture.request.entitySource?.copy(
                revisionFingerprint = "0".repeat(64),
            ),
        )

        val entityRejected = AggregateUpdateServiceChangeService.getInstance(project).preview(forgedEntity)
        assertFalse(entityRejected.accepted)
        assertTrue(entityRejected.issues.any { it.code == "JVW-AGGREGATE-SERVICE-ENTITY-STALE" })

        val staleController = fixture.request.copy(
            controllerSource = fixture.request.controllerSource?.copy(
                revisionFingerprint = "f".repeat(64),
            ),
        )
        val controllerRejected = AggregateUpdateServiceChangeService.getInstance(project).preview(staleController)
        assertFalse(controllerRejected.accepted)
        assertTrue(controllerRejected.issues.any { it.code == "JVW-AGGREGATE-SERVICE-CONTROLLER-STALE" })
    }

    fun testNestedCompositionInAnotherStoreIsRejectedBeforeAnyWritePlanExists() {
        val fixture = fixture(kotlinController = false, crossStoreComposition = true)

        val preview = AggregateUpdateServiceChangeService.getInstance(project).preview(fixture.request)

        assertFalse(preview.accepted)
        assertTrue(preview.files.isEmpty())
        assertTrue(preview.issues.any { it.code == "JVW-AGGREGATE-SERVICE-CROSS-STORE" })
        assertTrue(preview.issues.any { it.message.contains("LoanApp.lines") })
    }

    fun testExistingPlatformDelegateForEntityPreventsAnAmbiguousSecondService() {
        val fixture = fixture(kotlinController = false, existingPlatformDelegate = true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val preview = AggregateUpdateServiceChangeService.getInstance(project).preview(fixture.request)

        assertFalse(preview.accepted)
        assertTrue(preview.files.isEmpty())
        assertTrue(preview.issues.any { it.code == "JVW-AGGREGATE-SERVICE-DELEGATE-CONFLICT" })
        assertTrue(preview.issues.any { it.message.contains("ExistingLoanUpdateService") })
    }

    private fun fixture(
        kotlinController: Boolean,
        crossStoreComposition: Boolean = false,
        existingPlatformDelegate: Boolean = false,
    ): AggregateFixture {
        val root = getOrCreateProjectBaseDir()
        val languageRoot = if (kotlinController) "src/main/kotlin" else "src/main/java"
        val extension = if (kotlinController) "kt" else "java"
        val controllerContent = if (kotlinController) {
            """
            package com.acme.view.loan

            @ViewController("acme_LoanApp.detail")
            @ViewDescriptor("loan-app-detail-view.xml")
            class LoanAppDetailView {
                // handwritten Kotlin controller logic remains
            }
            """.trimIndent()
        } else {
            """
            package com.acme.view.loan;

            @ViewController("acme_LoanApp.detail")
            @ViewDescriptor("loan-app-detail-view.xml")
            public class LoanAppDetailView {
                // handwritten controller logic remains
            }
            """.trimIndent()
        }
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/entity/LoanApp.java",
                if (crossStoreComposition) {
                    """
                    package com.acme.entity;

                    @JmixEntity
                    @Entity
                    public class LoanApp {
                        @Id
                        private java.util.UUID id;

                        @Composition
                        @OneToMany
                        private java.util.List<LoanLine> lines;
                    }
                    """.trimIndent()
                } else {
                    """
                    package com.acme.entity;

                    @JmixEntity
                    @Entity
                    public class LoanApp {
                        @Id
                        private java.util.UUID id;
                    }
                    """.trimIndent()
                },
            )
            if (crossStoreComposition) {
                write(
                    root,
                    "src/main/java/com/acme/entity/LoanLine.java",
                    """
                    package com.acme.entity;

                    @JmixEntity
                    @Entity
                    @Store(name = "ledger")
                    public class LoanLine {
                        @Id
                        private java.util.UUID id;
                    }
                    """.trimIndent(),
                )
            }
            if (existingPlatformDelegate) {
                write(
                    root,
                    "src/main/java/io/jmix/core/SaveDelegate.java",
                    """
                    package io.jmix.core;
                    public interface SaveDelegate<E> {
                        E save(E entity, SaveContext saveContext);
                    }
                    """.trimIndent(),
                )
                write(
                    root,
                    "src/main/java/io/jmix/core/RemoveDelegate.java",
                    """
                    package io.jmix.core;
                    public interface RemoveDelegate<E> {
                        void remove(E entity);
                    }
                    """.trimIndent(),
                )
                write(
                    root,
                    "src/main/java/io/jmix/core/SaveContext.java",
                    "package io.jmix.core; public class SaveContext {}",
                )
                write(
                    root,
                    "src/main/java/com/acme/service/ExistingLoanUpdateService.java",
                    """
                    package com.acme.service;

                    public class ExistingLoanUpdateService
                            implements io.jmix.core.SaveDelegate<com.acme.entity.LoanApp> {
                        @Override
                        public com.acme.entity.LoanApp save(
                                com.acme.entity.LoanApp entity,
                                io.jmix.core.SaveContext saveContext
                        ) {
                            return entity;
                        }
                    }
                    """.trimIndent(),
                )
            }
            write(
                root,
                "$languageRoot/com/acme/view/loan/LoanAppDetailView.$extension",
                controllerContent,
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
                        <formLayout id="loanAppForm" dataContainer="loanAppDc"/>
                    </layout>
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
        if (kotlinController) {
            PsiTestUtil.addSourceRoot(
                module,
                requireNotNull(root.findFileByRelativePath("src/main/kotlin")),
                JavaSourceRootType.SOURCE,
            )
        }
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val descriptor = graph.artifacts.single {
            it.kind == ArtifactKind.VIEW_DESCRIPTOR && it.displayName == "acme_LoanApp.detail"
        }
        val entity = graph.artifacts.single {
            it.kind == ArtifactKind.ENTITY && it.semanticKey == "com.acme.entity.LoanApp"
        }
        val workspace = FlowUiWorkspaceService.getInstance(project).load(
            FlowUiWorkspaceRequest(descriptor.sourceLocator),
        )
        assertTrue(workspace.accepted, workspace.issues.joinToString { it.message })
        val controller = requireNotNull(workspace.controllerModel)
        return AggregateFixture(
            AggregateUpdateServiceRequest(
                descriptorSource = descriptor.sourceLocator,
                controllerSource = SourceLocator(
                    relativePath = controller.relativePath,
                    revisionFingerprint = controller.revisionFingerprint,
                ),
                entitySource = entity.sourceLocator,
                containerId = "loanAppDc",
                entityQualifiedName = "com.acme.entity.LoanApp",
            ),
        )
    }

    private fun write(root: VirtualFile, relativePath: String, content: String) {
        val file = VfsUtil.createDirectories(
            "${root.path}/${relativePath.substringBeforeLast('/')}",
        ).findOrCreateChildData(this, relativePath.substringAfterLast('/'))
        VfsUtil.saveText(file, content)
    }

    private data class AggregateFixture(
        val request: AggregateUpdateServiceRequest,
    )
}

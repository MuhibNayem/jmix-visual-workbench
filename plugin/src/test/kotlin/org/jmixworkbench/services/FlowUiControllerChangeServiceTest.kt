package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowUiControllerChangeServiceTest : HeavyPlatformTestCase() {
    fun testRepositoryLoadDelegateInjectsRepositoryAndPreservesLoaderContext() {
        val controller = controller()
        val repository = repository()
        val request = handlerRequest(
            controller = controller,
            repository = repository,
            kind = FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE,
            componentId = "loanAppsDl",
            componentTag = "loader",
        )

        val preview = FlowUiControllerChangeService.getInstance(project).previewHandler(request)

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val source = preview.files.single().resultContent
        assertTrue(source.contains("import com.acme.entity.LoanAppRepository;"))
        assertTrue(source.contains("import io.jmix.core.repository.JmixDataRepositoryContext;"))
        assertTrue(source.contains("import org.springframework.data.domain.Pageable;"))
        assertTrue(source.contains("@Autowired\n    private LoanAppRepository loanAppRepository;"))
        assertTrue(
            source.contains(
                "@Install(to = \"loanAppsDl\", target = Target.DATA_LOADER, " +
                    "subject = \"loadFromRepositoryDelegate\")",
            ),
        )
        assertTrue(
            source.contains(
                "return loanAppRepository.findAll(pageable, context).getContent();",
            ),
        )
        assertFalse(source.contains("unvalidated load delegate"))
    }

    fun testRepositoryDetailSaveDelegateFailsClosedForAggregateContexts() {
        val controller = controller()
        val repository = repository()

        val preview = FlowUiControllerChangeService.getInstance(project).previewHandler(
            handlerRequest(
                controller = controller,
                repository = repository,
                kind = FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE,
                componentId = "loanAppDc",
                componentTag = "instance",
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val source = preview.files.single().resultContent
        assertTrue(source.contains("@Install(target = Target.DATA_CONTEXT)"))
        assertTrue(source.contains("private Set<Object> loanAppRepositorySaveDelegate"))
        assertTrue(source.contains("saveContext.getEntitiesToSave().size() != 1"))
        assertTrue(source.contains("!saveContext.getEntitiesToRemove().isEmpty()"))
        assertTrue(source.contains("Use a transactional update service for aggregate saves."))
        assertTrue(source.contains("return Set.of(loanAppRepository.save(typedEntity));"))
    }

    fun testUnconstrainedOrMismatchedRepositoryIsNotWiredIntoUi() {
        val controller = controller()
        val unconstrained = repository(applyConstraints = false)
        val request = handlerRequest(
            controller = controller,
            repository = unconstrained,
            kind = FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE,
            componentId = "loanAppsDl",
            componentTag = "loader",
        )

        val securityRejected = FlowUiControllerChangeService.getInstance(project).previewHandler(request)

        assertFalse(securityRejected.accepted)
        assertTrue(
            securityRejected.issues.any { it.code == "JVW-CONTROLLER-REPOSITORY-SECURITY-BYPASS" },
            securityRejected.issues.toString(),
        )

        val mismatch = FlowUiControllerChangeService.getInstance(project).previewHandler(
            request.copy(entityClass = "com.acme.entity.Employee"),
        )
        assertFalse(mismatch.accepted)
        assertTrue(
            mismatch.issues.any { it.code == "JVW-CONTROLLER-REPOSITORY-ENTITY-MISMATCH" },
            mismatch.issues.toString(),
        )
    }

    fun testEffectiveFindAllMethodConstraintControlsUiWiring() {
        val controller = controller()
        val methodBypass = repository(
            invokedMethodConstraints = false,
        )
        val request = handlerRequest(
            controller = controller,
            repository = methodBypass,
            kind = FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE,
            componentId = "loanAppsDl",
            componentTag = "loader",
        )

        val methodRejected = FlowUiControllerChangeService.getInstance(project).previewHandler(request)

        assertFalse(methodRejected.accepted)
        assertTrue(
            methodRejected.issues.any { it.code == "JVW-CONTROLLER-REPOSITORY-SECURITY-BYPASS" },
            methodRejected.issues.toString(),
        )

        val explicitlyConstrained = repository(
            applyConstraints = false,
            invokedMethodConstraints = true,
        )
        val methodOverrideAccepted = FlowUiControllerChangeService.getInstance(project).previewHandler(
            request.copy(repositoryLocator = explicitlyConstrained),
        )
        assertTrue(
            methodOverrideAccepted.accepted,
            methodOverrideAccepted.issues.joinToString { "${it.code}: ${it.message}" },
        )
    }

    fun testInheritedMethodConstraintBypassFailsClosedWhenHierarchyIsUnresolved() {
        val controller = controller()
        val repository = repositoryWithInheritedBypass()
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val preview = FlowUiControllerChangeService.getInstance(project).previewHandler(
            handlerRequest(
                controller = controller,
                repository = repository,
                kind = FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE,
                componentId = "loanAppsDl",
                componentTag = "loader",
            ),
        )

        assertFalse(preview.accepted)
        assertTrue(
            preview.issues.any {
                it.code in setOf(
                    "JVW-CONTROLLER-REPOSITORY-SECURITY-BYPASS",
                    "JVW-CONTROLLER-REPOSITORY-SECURITY-UNPROVEN",
                )
            },
            preview.issues.toString(),
        )
    }

    fun testKotlinRepositoryLoadDelegateUsesPsiClassBoundaryAndPreservesSource() {
        val controller = kotlinController()
        val repository = repository()

        val preview = FlowUiControllerChangeService.getInstance(project).previewHandler(
            handlerRequest(
                controller = controller,
                repository = repository,
                kind = FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE,
                componentId = "loanAppsDl",
                componentTag = "loader",
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val source = preview.files.single().resultContent
        assertTrue(source.contains("// handwritten Kotlin controller logic remains in place"))
        assertTrue(source.contains("@org.springframework.beans.factory.annotation.Autowired"))
        assertTrue(
            source.contains(
                "private lateinit var loanAppRepository: com.acme.entity.LoanAppRepository",
            ),
        )
        assertTrue(source.contains("target = io.jmix.flowui.view.Target.DATA_LOADER"))
        assertTrue(source.contains("subject = \"loadFromRepositoryDelegate\""))
        assertTrue(source.contains("loanAppRepository.findAll(pageable, context).content"))
    }

    fun testKotlinRepositorySaveDelegateReusesConstructorInjectionAndFailsClosed() {
        val controller = kotlinController(existingConstructorInjection = true)
        val repository = repository()

        val preview = FlowUiControllerChangeService.getInstance(project).previewHandler(
            handlerRequest(
                controller = controller,
                repository = repository,
                kind = FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE,
                componentId = "loanAppDc",
                componentTag = "instance",
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val source = preview.files.single().resultContent
        assertFalse(source.contains("lateinit var loanAppRepository"))
        assertTrue(source.contains("saveContext.getEntitiesToSave().size != 1"))
        assertTrue(source.contains("saveContext.getEntitiesToRemove().isNotEmpty()"))
        assertTrue(source.contains("Use a transactional update service for aggregate saves."))
        assertTrue(source.contains("return setOf(loanAppRepository.save(entity))"))
    }

    private fun controller(): SourceLocator {
        val content = """
            package com.acme.view.loan;

            public class LoanAppListView {
                // handwritten controller logic remains in place
            }
        """.trimIndent()
        write("src/main/java/com/acme/view/loan/LoanAppListView.java", content)
        return locator("src/main/java/com/acme/view/loan/LoanAppListView.java", content)
    }

    private fun kotlinController(
        existingConstructorInjection: Boolean = false,
    ): SourceLocator {
        val constructor = if (existingConstructorInjection) {
            """
            (
                private val loanAppRepository: com.acme.entity.LoanAppRepository,
            )
            """.trimIndent()
        } else {
            ""
        }
        val content = """
            package com.acme.view.loan

            class LoanAppListView$constructor {
                // handwritten Kotlin controller logic remains in place
            }
        """.trimIndent()
        write("src/main/kotlin/com/acme/view/loan/LoanAppListView.kt", content)
        return locator("src/main/kotlin/com/acme/view/loan/LoanAppListView.kt", content)
    }

    private fun repository(
        applyConstraints: Boolean = true,
        invokedMethodConstraints: Boolean? = null,
    ): SourceLocator {
        val security = if (applyConstraints) "" else "@ApplyConstraints(false)\n"
        val method = invokedMethodConstraints?.let { constraints ->
            """

                @Override
                @ApplyConstraints($constraints)
                org.springframework.data.domain.Page<LoanApp> findAll(
                    org.springframework.data.domain.Pageable pageable,
                    io.jmix.core.repository.JmixDataRepositoryContext context
                );
            """.trimIndent()
        }.orEmpty()
        val content = """
            package com.acme.entity;

            import io.jmix.core.repository.ApplyConstraints;
            import io.jmix.core.repository.JmixDataRepository;
            import java.util.UUID;

            ${security}public interface LoanAppRepository extends JmixDataRepository<LoanApp, UUID> {
                $method
            }
        """.trimIndent()
        write("src/main/java/com/acme/entity/LoanAppRepository.java", content)
        return locator("src/main/java/com/acme/entity/LoanAppRepository.java", content)
    }

    private fun repositoryWithInheritedBypass(): SourceLocator {
        write(
            "src/main/java/com/acme/entity/SecurityBaseRepository.java",
            """
            package com.acme.entity;

            import io.jmix.core.repository.ApplyConstraints;

            public interface SecurityBaseRepository {
                @ApplyConstraints(false)
                org.springframework.data.domain.Page<LoanApp> findAll(
                    org.springframework.data.domain.Pageable pageable,
                    io.jmix.core.repository.JmixDataRepositoryContext context
                );
            }
            """.trimIndent(),
        )
        val content = """
            package com.acme.entity;

            import io.jmix.core.repository.JmixDataRepository;
            import java.util.UUID;

            public interface LoanAppRepository
                    extends JmixDataRepository<LoanApp, UUID>, SecurityBaseRepository {
            }
        """.trimIndent()
        write("src/main/java/com/acme/entity/LoanAppRepository.java", content)
        return locator("src/main/java/com/acme/entity/LoanAppRepository.java", content)
    }

    private fun handlerRequest(
        controller: SourceLocator,
        repository: SourceLocator,
        kind: FlowUiControllerHandlerKind,
        componentId: String,
        componentTag: String,
    ) = FlowUiControllerHandlerRequest(
        controllerLocator = controller,
        kind = kind,
        componentId = componentId,
        componentTag = componentTag,
        entityClass = "com.acme.entity.LoanApp",
        repositoryLocator = repository,
        repositoryQualifiedName = "com.acme.entity.LoanAppRepository",
    )

    private fun locator(path: String, content: String) = SourceLocator(
        relativePath = path,
        revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
    )

    private fun write(path: String, content: String) {
        WriteAction.run<RuntimeException> {
            val root = getOrCreateProjectBaseDir()
            val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
            VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
        }
    }
}

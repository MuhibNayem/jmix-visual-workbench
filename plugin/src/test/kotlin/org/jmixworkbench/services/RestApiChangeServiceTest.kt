package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestApiChangeServiceTest : HeavyPlatformTestCase() {
    fun testPreviewAndAtomicApplyPreserveExistingEnterpriseConfiguration() {
        val path = "src/main/resources/rest/rest-services.xml"
        val original = """
            <?xml version="1.0" encoding="UTF-8"?>
            <services xmlns="http://jmix.io/schema/rest/services" review-owner="architecture">
                <!-- manual bank integration contract -->
                <extension audit="strict"/>
                <service name="loan_LoanService" manual="preserve">
                    <method name="existing"/>
                </service>
            </services>
        """.trimIndent() + "\n"
        addFile(path, original)
        ApplicationGraphService.getInstance(project).invalidate()
        val workspace = RestApiWorkspaceService.getInstance(project).load(true)
        val config = workspace.configs.single { it.sourceLocator.relativePath == path }
        val request = RestApiContractAdditionRequest(
            moduleId = config.moduleId,
            configLocator = config.sourceLocator,
            contract = RestApiContractInput.ServiceMethod(
                serviceName = "loan_LoanService",
                methodName = "settle",
                parameters = listOf(
                    RestApiContractParameterInput("loanId", "java.util.UUID"),
                ),
            ),
        )
        val service = RestApiChangeService.getInstance(project)

        val preview = service.previewAddition(request)

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val reviewed = preview.files.single().resultContent
        assertContains(reviewed, "<!-- manual bank integration contract -->")
        assertContains(reviewed, """<extension audit="strict"/>""")
        assertContains(reviewed, """manual="preserve"""")
        assertContains(reviewed, """<method name="settle">""")
        val prepared = service.prepareAddition(
            RestApiContractAdditionApplyRequest(request, requireNotNull(preview.planDigest)),
        )
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString { it.message })
        val written = readFile(path)
        assertContains(written, "<!-- manual bank integration contract -->")
        assertContains(written, """<param name="loanId" type="java.util.UUID"/>""")
    }

    fun testStaleOrWrongKindConfigurationIsRejectedBeforeMutation() {
        val path = "src/main/resources/rest/rest-queries.xml"
        val original = """
            <queries xmlns="http://jmix.io/schema/rest/queries">
            </queries>
        """.trimIndent()
        addFile(path, original)
        ApplicationGraphService.getInstance(project).invalidate()
        val config = RestApiWorkspaceService.getInstance(project).load(true).configs
            .single { it.sourceLocator.relativePath == path }
        val stale = config.sourceLocator.copy(revisionFingerprint = "stale")

        val preview = RestApiChangeService.getInstance(project).previewAddition(
            RestApiContractAdditionRequest(
                moduleId = config.moduleId,
                configLocator = stale,
                contract = RestApiContractInput.Query(
                    name = "byState",
                    entityName = "loan_LoanApp",
                    fetchPlan = "_base",
                    jpql = "select e from loan_LoanApp e where e.state = :state",
                    parameters = listOf(RestApiContractParameterInput("state", "java.lang.String")),
                ),
            ),
        )

        assertFalse(preview.accepted)
        assertTrue(preview.issues.any { it.code == "JVW-REST-CONFIG-STALE" })
        assertTrue(readFile(path) == original)
    }

    fun testUpdateAndRemovalUseRevisionLockedSurgicalChanges() {
        val path = "src/main/resources/rest/rest-queries.xml"
        val original = """
            <queries xmlns="http://jmix.io/schema/rest/queries">
                <!-- risk-owned configuration -->
                <query name="byState" entity="loan_LoanApp" fetchPlan="_base" cacheable="true">
                    <jpql timeout="strict"><![CDATA[select e from loan_LoanApp e where e.state = :state]]></jpql>
                    <params>
                        <param name="state" type="java.lang.String"/>
                        <parameter-extension owner="risk"/>
                    </params>
                    <result-policy mask="salary"/>
                </query>
            </queries>
        """.trimIndent() + "\n"
        addFile(path, original)
        ApplicationGraphService.getInstance(project).invalidate()
        var loadedWorkspace = RestApiWorkspaceService.getInstance(project).load(true)
        val indexedQuery = loadedWorkspace.operations.single { it.kind == RestApiOperationKind.QUERY }
        assertTrue(indexedQuery.queryText == "select e from loan_LoanApp e where e.state = :state")
        assertTrue(indexedQuery.fetchPlanName == "_base")
        var config = loadedWorkspace.configs
            .single { it.sourceLocator.relativePath == path }
        val service = RestApiChangeService.getInstance(project)
        val update = RestApiContractMutationRequest(
            moduleId = config.moduleId,
            configLocator = config.sourceLocator,
            mode = RestApiContractMutationMode.UPDATE,
            target = RestApiContractTargetInput.Query("byState", "loan_LoanApp"),
            replacement = RestApiContractInput.Query(
                name = "approvedByBranch",
                entityName = "loan_LoanApp",
                fetchPlan = "loan-with-account",
                jpql = "select e from loan_LoanApp e where e.state = :state and e.branch.code = :branch",
                parameters = listOf(
                    RestApiContractParameterInput("state", "java.lang.String"),
                    RestApiContractParameterInput("branch", "java.lang.String"),
                ),
            ),
        )
        val updatePreview = service.previewMutation(update)
        assertTrue(updatePreview.accepted, updatePreview.issues.joinToString { it.message })
        val updateApplied = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepareMutation(
                RestApiContractMutationApplyRequest(update, requireNotNull(updatePreview.planDigest)),
            ),
        )
        assertTrue(updateApplied.success, updateApplied.issues.joinToString { it.message })
        val updated = readFile(path)
        assertContains(updated, """name="approvedByBranch"""")
        assertContains(updated, """cacheable="true"""")
        assertContains(updated, """timeout="strict"""")
        assertContains(updated, """<parameter-extension owner="risk"/>""")
        assertContains(updated, """<result-policy mask="salary"/>""")

        ApplicationGraphService.getInstance(project).invalidate()
        loadedWorkspace = RestApiWorkspaceService.getInstance(project).load(true)
        config = loadedWorkspace.configs
            .single { it.sourceLocator.relativePath == path }
        val remove = RestApiContractMutationRequest(
            moduleId = config.moduleId,
            configLocator = config.sourceLocator,
            mode = RestApiContractMutationMode.REMOVE,
            target = RestApiContractTargetInput.Query("approvedByBranch", "loan_LoanApp"),
        )
        val removePreview = service.previewMutation(remove)
        assertTrue(removePreview.accepted, removePreview.issues.joinToString { it.message })
        val removeApplied = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepareMutation(
                RestApiContractMutationApplyRequest(remove, requireNotNull(removePreview.planDigest)),
            ),
        )
        assertTrue(removeApplied.success, removeApplied.issues.joinToString { it.message })
        val removed = readFile(path)
        assertContains(removed, "<!-- risk-owned configuration -->")
        assertFalse("approvedByBranch" in removed)
    }

    private fun addFile(path: String, content: String) {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
            val file = parent.findOrCreateChildData(this, path.substringAfterLast('/'))
            VfsUtil.saveText(file, content)
        }
        val resourceRoot = requireNotNull(root.findFileByRelativePath("src/main/resources"))
        PsiTestUtil.addSourceRoot(module, resourceRoot, JavaResourceRootType.RESOURCE)
    }

    private fun readFile(path: String): String {
        val file = requireNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(path))
        return String(file.contentsToByteArray(false), file.charset)
    }
}
